# MongoDB 实现说明

> **本文结构**：作用 → 实现方式 → 步骤与代码 → 设计决策 → 原理 → 不足
> **配套**：其他组件见同目录《Elasticsearch 实现说明》《HDFS 实现说明》《Kafka 实现说明》《Nginx 实现说明》

---

## 一、在项目中做什么

MongoDB 是系统的**权威数据存储**——所有结构化数据以它为准，Elasticsearch 中的索引只是可重建的副本。

**表 1-1 集合清单**

| 集合 | 类型 | 用途 | TTL |
| :--- | :--- | :--- | :--- |
| `device_status` | 普通 | 设备台账与最新状态 | 无 |
| `telemetry` | **时序** | 巡检轨迹（GPS） | 7 天 |
| `sensor_data` | **时序** | 环境传感器读数 | 7 天 |
| `task_log` | **时序** | 任务回执流水 | 7 天 |
| `alarm` | 普通 | 告警明细（权威数据） | 无 |
| `media_meta` | 普通 | 影像元数据 | 无 |
| `task` | 普通 | 任务台账（当前状态） | 无 |
| `user` | 普通 | 用户与角色 | 无 |
| `counter` | 普通 | 原子计数器（编号生成） | 无 |

**数据分类的三种读写模式**：

| 类型 | 特征 | 代表集合 | 存储选择 |
| :--- | :--- | :--- | :--- |
| **最新值型** | 每实体一条，反复覆盖 | `device_status` | 普通集合 |
| **时间序列型** | 只追加、量大、有时效 | `telemetry`、`sensor_data`、`task_log` | **时序集合 + TTL** |
| **业务实体型** | 有生命周期、会被更新 | `alarm`、`task`、`media_meta` | 普通集合 |

---

## 二、实现方式

### 2.1 技术选择：Spring Data MongoDB + MongoTemplate 并用

| 工具 | 适用场景 | 本项目用途 |
| :--- | :--- | :--- |
| `MongoRepository` | 固定条件查询（方法名可表达） | `findById`、`findByStatus`、`count` |
| **`MongoTemplate`** | **动态条件、批量写入、原子操作** | 攒批写入、多条件动态查询、`findAndModify` |

**为什么不只用 Repository**：Repository 的方法名写法无法表达"6 个条件任意组合"（共 2⁶ 种）这类动态查询，也无法批量写入。因此二者并用，各取所长。

---

## 三、步骤与代码

### 步骤 1：部署并限制缓存

```yaml
# docker-compose.yml
mongodb:
  image: mongo:8.3.9
  container_name: mongodb
  command: mongod --wiredTigerCacheSizeGB 0.5      # 限制 WiredTiger 缓存
  ports:
    - "27017:27017"
  volumes:
    - mongodb-data:/data/db
    - ./init/mongo-init.js:/docker-entrypoint-initdb.d/mongo-init.js:ro
  healthcheck:
    test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
    interval: 10s
```

> **`--wiredTigerCacheSizeGB 0.5`**：MongoDB 默认会占用"可用内存的一半"作为缓存。在 8 GB 共享配额的环境下，这会与其他容器争抢内存，因此显式限制为 512 MB。

### 步骤 2：初始化脚本

MongoDB 官方镜像会在**数据目录为空时**自动执行挂载到 `/docker-entrypoint-initdb.d/` 的脚本：

```javascript
db = db.getSiblingDB('uav_platform');

/** 幂等地创建时序集合 */
function ensureTimeSeries(name, timeField, metaField, expireSeconds) {
  try {
    db.createCollection(name, {
      timeseries: { timeField: timeField, metaField: metaField, granularity: 'seconds' },
      expireAfterSeconds: expireSeconds
    });
  } catch (e) {
    if (e.codeName === 'NamespaceExists') {
      print('[mongo-init] 时序集合 ' + name + ' 已存在，跳过');
    } else {
      throw e;
    }
  }
}

// 时序集合：高吞吐写入 + 7 天自动过期
ensureTimeSeries('telemetry',   'eventTime', 'deviceNo', 604800);
ensureTimeSeries('sensor_data', 'eventTime', 'deviceNo', 604800);
ensureTimeSeries('task_log',    'eventTime', 'deviceNo', 604800);

// 辅助索引
db.device_status.createIndex({ status: 1 });
db.alarm.createIndex({ alarmType: 1, eventTime: -1 });
db.alarm.createIndex({ handleStatus: 1 });
db.media_meta.createIndex({ uploaded: 1 });

// 任务台账（普通集合：会被反复更新，不能用时序集合）
db.task.createIndex({ status: 1, createTime: -1 });
db.task.createIndex({ deviceNo: 1, createTime: -1 });
```

> **为什么把 `createCollection` 包进 try/catch**：脚本在集合已存在时会抛 `NamespaceExists` 并中断，导致后续索引全部建不上。包起来后脚本**可重复执行**，对已运行的环境也能直接重跑。

### 步骤 3：创建时序集合

```javascript
db.createCollection('telemetry', {
  timeseries: {
    timeField: 'eventTime',     // 时间字段（必须是 Date 类型）
    metaField: 'deviceNo',      // 元数据字段（同设备归一组，便于压缩）
    granularity: 'seconds'      // 数据点的时间粒度
  },
  expireAfterSeconds: 604800    // 7 天后自动删除
});
```

**表 3-1 时序集合的参数含义**

| 参数 | 作用 |
| :--- | :--- |
| `timeField` | 指定时间字段，**必须是 BSON `Date` 类型**，是分桶的依据 |
| `metaField` | 指定归组字段，同源数据被压缩进同一批桶中 |
| `granularity` | 声明数据点的时间粒度，影响分桶的时间跨度 |
| `expireAfterSeconds` | 文档自动过期时长，免去人工清理 |

### 步骤 4：配置连接

```properties
# application.properties
# 注意：Spring Boot 4 已将该前缀由 spring.data.mongodb 变更为 spring.mongodb
spring.mongodb.uri=mongodb://localhost:27017/uav_platform
```

```yaml
# 容器内通过环境变量覆盖
SPRING_MONGODB_URI: mongodb://mongodb:27017/uav_platform
```

> **踩坑提示**：Spring Boot 4 将此配置前缀由 `spring.data.mongodb.*` 改为 `spring.mongodb.*`。**旧前缀不会报错，而是被静默忽略并回退为默认值 `localhost:27017`**。排查"配置没生效"时，应优先怀疑属性名在版本升级中发生了变更。

### 步骤 5：批量写入（攒批 + 超时兜底）

```java
@Service
public class TelemetryService {

    private static final int BATCH_SIZE = 100;      // 攒够 100 条写一次

    private final MongoTemplate mongoTemplate;
    private final List<TelemetryDoc> buffer = new ArrayList<>(BATCH_SIZE);

    /** 收一条轨迹：进缓冲区，够一批就写 */
    public synchronized void add(GpsMessage gps) {
        TelemetryDoc doc = new TelemetryDoc();
        doc.setDeviceNo(gps.deviceNo());
        doc.setLat(gps.lat());
        doc.setLng(gps.lng());
        doc.setEventTime(new Date(gps.eventTime()));       // long → Date（时序集合要求）
        doc.setIngestTime(System.currentTimeMillis());

        buffer.add(doc);
        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /** 定时兜底：每 2 秒把缓冲区里剩下的写掉 */
    @Scheduled(fixedRate = 2000)
    public synchronized void flushByTime() {
        flush();
    }

    /** 批量写入：一次网络往返写 N 条 */
    private void flush() {
        if (buffer.isEmpty()) return;
        int size = buffer.size();
        mongoTemplate.insert(buffer, TelemetryDoc.class);
        buffer.clear();
        log.info("[批量写入] 轨迹数据 {} 条已入库", size);
    }
}
```

**双保险的意义**：

- 仅有批量阈值时，**低峰期数据会长时间停留内存**；
- 加上定时兜底后，无论消息量高低，数据都能在 2 秒内落库。

` synchronized` 保证多线程消费下缓冲区的安全。

> **踩坑提示**：时序集合的 `timeField` 必须是 `Date` 类型。若写入 long 型时间戳，会报错
> `'eventTime' must be present and contain a valid BSON UTC datetime value`。

### 步骤 6：多条件动态查询

```java
public Map<String, Object> queryAlarms(String status, String deviceNo, String alarmType,
                                       String level, Long startTime, Long endTime,
                                       int page, int size) {
    List<Criteria> conditions = new ArrayList<>();

    if (status != null && !status.isBlank() && !"ALL".equals(status)) {
        conditions.add(Criteria.where("handleStatus").is(status));
    }
    if (deviceNo != null && !deviceNo.isBlank()) {
        conditions.add(Criteria.where("deviceNo").is(deviceNo));
    }
    if (alarmType != null && !alarmType.isBlank()) {
        conditions.add(Criteria.where("alarmType").is(alarmType));
    }
    if (level != null && !level.isBlank()) {
        conditions.add(Criteria.where("level").is(level));
    }
    if (startTime != null) {
        conditions.add(Criteria.where("eventTime").gte(startTime));
    }
    if (endTime != null) {
        conditions.add(Criteria.where("eventTime").lte(endTime));
    }

    Query query = new Query();
    if (!conditions.isEmpty()) {
        query.addCriteria(new Criteria().andOperator(conditions.toArray(new Criteria[0])));
    }

    long total = mongoTemplate.count(query, AlarmDoc.class);

    query.with(Sort.by(Sort.Direction.DESC, "eventTime"))
         .skip((long) Math.max(page - 1, 0) * size)
         .limit(size);

    List<AlarmDoc> items = mongoTemplate.find(query, AlarmDoc.class);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("total", total);
    resp.put("items", items);
    return resp;
}
```

**为什么用动态拼接而非方法名**：6 个可选条件共 2⁶ = 64 种组合，用 Repository 方法名写法无法表达。

### 步骤 7：原子计数器生成业务编号

```java
public String nextTaskId() {
    String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    CounterDoc counter = mongoTemplate.findAndModify(
            Query.query(Criteria.where("_id").is("task-" + day)),
            new Update().inc("seq", 1L),
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            CounterDoc.class);

    long seq = (counter == null || counter.getSeq() == null) ? 1L : counter.getSeq();
    return "TASK-" + day + "-" + String.format("%04d", seq);      // TASK-20260918-0001
}
```

**`findAndModify` 的三个要点**：

| 选项 | 作用 |
| :--- | :--- |
| `upsert(true)` | 当天首次调用时自动创建计数器文档 |
| `inc("seq", 1)` | **原子自增**，两个线程同时来只会拿到不同的值 |
| `returnNew(true)` | 返回自增**之后**的值（而非之前） |

---

## 四、设计决策

### 决策 1：为什么选 MongoDB 而非 HBase

| 维度 | MongoDB（选定） | HBase（备选） |
| :--- | :--- | :--- |
| 数据模型 | 文档（BSON），字段灵活 | 宽表（列族），需预先设计 Schema |
| 查询能力 | 支持条件 / 范围 / 聚合查询 | 主键与范围扫描为主 |
| 开发迭代 | 快速，适合业务频繁调整 | 较重，适合超大规模型 |
| 部署复杂度 | 单节点或副本集即可 | 强依赖 HDFS + ZooKeeper |
| 与本项目匹配度 | 按编号 / 时间查询直接映射 | 数据量未达海量级，优势难发挥 |

**选定理由**：本项目结构化数据体量适中，查询以"按设备编号、时间范围"为主，MongoDB 的文档模型与查询能力更贴合。此外 MongoDB 提供的**时序集合**特性恰好解决了遥测数据的存储优化与自动过期问题，这是 HBase 不具备的便利。

### 决策 2：为什么用时序集合 ★

**这是本组件最核心的设计决策。**

对于带时间戳的测量类数据（轨迹、传感、回执），时序集合带来三个明确收益：

| 收益 | 机制 |
| :--- | :--- |
| **存储占用大幅降低** | 同设备相邻时间的数据被压缩进同一个"桶"（Bucket），而非每条独立存储 |
| **索引体积降低** | 桶级索引取代了每条记录都建索引 |
| **自动过期** | `expireAfterSeconds` 让历史数据自动清理，无需人工归档 |
| **查询效率提升** | 按时间范围查询时，只需扫描相关桶 |

**什么样的数据适合时序集合**：

```text
✓ 适合：每条记录带时间戳、只追加不修改、按时间范围查询、有保留期限
        → 轨迹、传感读数、执行日志、监控指标

✗ 不适合：需要频繁更新单条记录、需要按任意字段做复杂查询
        → 任务台账、告警明细（要更新处置状态）
```

**为什么 `task_log` 用时序集合而 `task` 不用**：前者是只追加的流水，后者会被频繁更新（状态流转、进度推进）——**"是否会更新"是区分的关键判据**。

### 决策 3：为什么"攒批 + 超时兜底"

逐条 `insert()` 会产生大量网络往返。批量写入将 N 次往返压缩为 1 次，是高吞吐场景的关键优化。

但**仅有批量阈值是不够的**——低峰期数据会长时间停留在内存中不落库。加上 `@Scheduled` 定时兜底后，无论消息量高低，数据都能在 2 秒内入库。

> 这是一个通用模式：**批量优化吞吐，定时兜底保证时效**。二者缺一不可。

### 决策 4：台账与回执流水分离

| 集合 | 性质 | 操作模式 | 存储类型 |
| :--- | :--- | :--- | :--- |
| `task` | 任务**当前状态**，一个任务一份 | 频繁更新 | 普通集合 |
| `task_log` | 任务**执行流水**，只追加 | 只插入 | 时序集合（7 天 TTL） |

**为什么分离**：两者的读写模式完全不同。任务台账需要频繁更新单个文档（进度 0→50→100），适合普通集合；执行流水是只追加的时间序列，适合时序集合并能自动过期。

若混在一张表中，就不得不在时序集合上执行更新操作，**既低效又违背其设计初衷**。

> 这一分离是**读写模型分离（CQRS）** 思想的简化实践：写模型（流水）与读模型（台账）分开，各用最适合的存储方式。

### 决策 5：编号由数据库原子生成

**原先的实现**（存在缺陷）：

```java
String taskId = "TASK-" + System.currentTimeMillis();      // 仅有毫秒精度
```

批量处置是**循环调用单条逻辑**，一轮循环往往不到 1 毫秒，导致多个任务获得相同编号；而指令标识 `msgId` 由 taskId 派生，因此也重复，最终被设备侧的幂等保护判定为"重复投递"而**静默丢弃**。

**改进后**：使用数据库原子计数器。

**为什么不用内存计数器**：JVM 重启后内存计数器会从 1 重新开始，与当天已存在的编号碰撞。数据库的 `findAndModify` 是原子操作，可保证**跨进程、跨重启唯一**。

### 决策 6：索引按查询模式设计

| 集合 | 索引 | 支撑的查询 |
| :--- | :--- | :--- |
| `alarm` | `{alarmType:1, eventTime:-1}` | 按类型筛选 + 按时间排序 |
| `alarm` | `{handleStatus:1}` | 按处置状态筛选 |
| `task` | `{status:1, createTime:-1}` | 任务列表按状态筛选 + 时间倒序 |
| `task` | `{deviceNo:1, createTime:-1}` | 任务列表按设备筛选 |
| `media_meta` | `{uploaded:1}` | 查询上传失败的记录 |

**索引设计原则**：复合索引的字段顺序应遵循"**等值条件在前、范围/排序条件在后**"。例如 `{alarmType:1, eventTime:-1}` 中 `alarmType` 是等值匹配、`eventTime` 用于排序，这个顺序才能同时用上索引的筛选与排序能力。

---

## 五、原理层面

### 5.1 文档模型

数据以 BSON（类 JSON 的二进制格式）文档存储，文档组织在集合中。与关系型数据库的对应：文档 ↔ 行，集合 ↔ 表。

**核心特点是无需预先定义 Schema**：同一集合中的文档可以拥有不同字段。这使业务字段变更时无需执行 DDL 迁移。

### 5.2 时序集合的分桶机制

```text
传统存储（每条独立）：
  桶内的每条记录独立存储 + 独立索引

时序集合（分桶）：
  ┌─────────────────────────────────────────┐
  │ Bucket                                  │
  │  meta: DOG-001                          │
  │  timeRange: 10:00:00 ~ 10:01:00         │
  │  data: [ {t:..., v:...}, {t:..., v:...} ]│  ← 列式压缩存储
  └─────────────────────────────────────────┘
  同一设备的相邻数据被打包进同一个桶
```

**收益来源**：同设备的数据具有高度相似性（字段相同、数值连续），列式压缩效果极好。同时，桶的数量远少于记录数量，**索引体积随之大幅降低**。

### 5.3 WiredTiger 存储引擎

MongoDB 3.0 起的默认存储引擎，采用 B-Tree 索引 + 文档级并发控制，并支持压缩（默认 Snappy）。本项目的 WiredTiger 缓存限制为 512 MB。

> **实测观察**：项目运行期间，512 MB 的缓存**仅使用了 6.2 MB**。这说明当前数据量距离内存瓶颈极远——**系统的瓶颈完全不在存储容量上，而在吞吐链路上**。

### 5.4 `skip/limit` 分页的代价

`skip(n)` 的执行方式是**先扫描并丢弃前 n 条文档**。因此：

| 页码 | 需要扫描并丢弃的文档数 |
| ---: | ---: |
| 1 | 0 |
| 100 | 990 |
| 500 | 4990 |

**复杂度为 O(页码)**。优化方向是**游标分页**：以上一页最后一条记录的时间戳作为下一页的查询起点，复杂度降为 O(1)。

---

## 六、当前不足与优化方向

| # | 不足 | 影响 | 优化方向 |
| :--- | :--- | :--- | :--- |
| 1 | **深分页随页码衰减** | `skip/limit` 复杂度为 O(页码)，翻到后面越来越慢 | 改为游标分页（以最后一条的时间戳为起点） |
| 2 | **单实例，无副本集** | 无高可用能力，实例故障即数据不可用 | 部署 3 节点副本集 |
| 3 | **无分片** | 单机容量有上限 | 数据量增长后可启用分片 |
| 4 | **每页查询 2 次往返** | 先 `count()` 再 `find()`，共 2 次网络往返 | 可接受；或改用 `countDocuments` 与新接口 |
| 5 | **部分字段缺索引** | `alarm` 的 `level`、`deviceNo` 无独立索引 | 按实际查询模式补充索引 |
| 6 | **`findAll()` 全表加载** | 离线检测每 5 秒全表加载设备表；统计为取 size 加载全部文档 | 改用条件查询或聚合 |
| 7 | **连接池未显式配置** | 使用驱动默认 `maxPoolSize=100` | 按并发量显式配置 |
| 8 | **索引未在应用层声明** | 索引仅存在于初始化脚本，依赖数据卷为空时执行 | 使用 `@Indexed` / `@CompoundIndex` 注解或在应用启动时确保索引 |

---

## 附：一页速记

```text
【作用】权威数据存储（ES 只是可重建的副本）
        9 个集合 = 3 个时序集合 + 6 个普通集合
【方式】MongoRepository（固定查询）+ MongoTemplate（动态查询 / 批量 / 原子操作）
【三类数据】
    · 最新值型   → device_status     → 普通集合，反复覆盖
    · 时间序列型 → telemetry / sensor_data / task_log → 时序集合 + 7 天 TTL
    · 业务实体型 → alarm / task / media_meta → 普通集合，会被更新
【时序集合四参数】
    timeField（必须 Date）/ metaField / granularity / expireAfterSeconds
【写入模式】攒批 BATCH_SIZE + @Scheduled 超时兜底
【关键决策】
    · 台账与流水分离（task 普通 / task_log 时序）← CQRS 简化实践
    · 编号用 findAndModify 原子自增（非时间戳、非内存计数器）
    · 索引遵循"等值在前、范围/排序在后"
【踩坑】Spring Boot 4 前缀变更 spring.data.mongodb.* → spring.mongodb.*（旧的静默失效）
【不足】深分页 O(page)、无副本集、无分片、部分字段缺索引、findAll 全表加载
```
