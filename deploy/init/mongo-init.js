// ============================================================
// MongoDB 初始化脚本
//   · 挂载到容器的 /docker-entrypoint-initdb.d/ 目录
//   · MongoDB 官方镜像会在【数据目录为空时】自动执行（即首次启动）
//   · 作用：创建时序集合（普通集合无需预建，写入时自动创建）
// ============================================================

db = db.getSiblingDB('uav_platform');

// 巡检轨迹（GPS）—— 时序集合：高吞吐写入 + 自动过期
db.createCollection('telemetry', {
  timeseries: {
    timeField: 'eventTime',     // 时间字段（必须是 Date 类型）
    metaField: 'deviceNo',      // 元数据字段（同设备归一组，便于压缩）
    granularity: 'seconds'
  },
  expireAfterSeconds: 604800    // 7 天后自动删除
});

// 环境传感器读数 —— 时序集合
db.createCollection('sensor_data', {
  timeseries: { timeField: 'eventTime', metaField: 'deviceNo', granularity: 'seconds' },
  expireAfterSeconds: 604800
});

// 任务执行日志 —— 时序集合
db.createCollection('task_log', {
  timeseries: { timeField: 'eventTime', metaField: 'deviceNo', granularity: 'seconds' },
  expireAfterSeconds: 604800
});

// 辅助索引（普通集合：设备状态 / 告警）
db.device_status.createIndex({ status: 1 });
db.alarm.createIndex({ alarmType: 1, eventTime: -1 });
db.alarm.createIndex({ handleStatus: 1 });
db.media_meta.createIndex({ uploaded: 1 });

print('[mongo-init] uav_platform 时序集合与索引初始化完成');
