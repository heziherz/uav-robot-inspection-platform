// ============================================================
// MongoDB 初始化脚本
//   · 挂载到容器的 /docker-entrypoint-initdb.d/ 目录
//   · MongoDB 官方镜像会在【数据目录为空时】自动执行（即首次启动）
//   · 作用：创建时序集合、建索引
//
// ★ 本脚本设计为【可重复执行】：
//   原来直接在顶层调 createCollection，集合已存在时会抛 NamespaceExists 导致
//   整个脚本中断，后续索引一条都建不上。现在用 try/catch 包起来，
//   于是对已运行的环境也能直接重跑（见 deploy/README.md 的手动执行命令）。
//   —— 索引创建本身是幂等的（相同 spec 重复创建是 no-op），无需额外处理。
// ============================================================

db = db.getSiblingDB('uav_platform');

/** 幂等地创建时序集合 */
function ensureTimeSeries(name, timeField, metaField, expireSeconds) {
  try {
    db.createCollection(name, {
      timeseries: {
        timeField: timeField,     // 时间字段（必须是 Date 类型）
        metaField: metaField,     // 元数据字段（同设备归一组，便于压缩）
        granularity: 'seconds'
      },
      expireAfterSeconds: expireSeconds
    });
    print('[mongo-init] 已创建时序集合 ' + name);
  } catch (e) {
    if (e.codeName === 'NamespaceExists') {
      print('[mongo-init] 时序集合 ' + name + ' 已存在，跳过');
    } else {
      throw e;
    }
  }
}

// ---------- 时序集合：高吞吐写入 + 自动过期（7 天）----------
ensureTimeSeries('telemetry',   'eventTime', 'deviceNo', 604800);   // 巡检轨迹（GPS）
ensureTimeSeries('sensor_data', 'eventTime', 'deviceNo', 604800);   // 环境传感器读数
ensureTimeSeries('task_log',    'eventTime', 'deviceNo', 604800);   // 任务执行日志（回执流水）

// ---------- 辅助索引（普通集合）----------
db.device_status.createIndex({ status: 1 });
db.alarm.createIndex({ alarmType: 1, eventTime: -1 });
db.alarm.createIndex({ handleStatus: 1 });
db.media_meta.createIndex({ uploaded: 1 });

// ---------- 任务台账（普通集合：会被反复更新状态/进度，不能用时序集合）----------
// 注意：不要再建 { taskId: 1 } 的唯一索引。
//   TaskDoc 里 `@Id private String taskId` 映射到 MongoDB 的【_id】字段，
//   文档中并不存在独立的 taskId 字段；在它上面建索引会让所有文档都索引成 null，
//   导致第二条插入必然报 E11000 duplicate key。_id 本身已经保证唯一，无需额外索引。
db.task.createIndex({ status: 1, createTime: -1 });          // 列表页：按状态筛选 + 时间倒序
db.task.createIndex({ deviceNo: 1, createTime: -1 });        // 列表页：按设备筛选

// 任务详情页要按 taskId 回查回执流水。
// 时序集合上的二级索引在不同版本支持程度不同，失败不影响功能（只是全表扫），
// 因此这里单独容错，不让它中断整个初始化脚本。
try {
  db.task_log.createIndex({ taskId: 1 });
  print('[mongo-init] task_log.taskId 索引已创建');
} catch (e) {
  print('[mongo-init] task_log.taskId 索引创建失败（不影响功能）: ' + e.message);
}

print('[mongo-init] uav_platform 集合与索引初始化完成');
