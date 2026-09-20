# Kafka 实现说明

> **本文结构**：作用 → 实现方式 → 步骤与代码 → 设计决策 → 原理 → 不足
> **配套**：其他组件见同目录《Elasticsearch 实现说明》《HDFS 实现说明》《MongoDB 实现说明》《Nginx 实现说明》

---

## 一、在项目中做什么

Kafka 是系统的**中枢神经**，承担设备与平台之间的双向通信。

**表 1-1 双向消息通道**

| 方向 | 主题数 | 内容 |
| :--- | ---: | :--- |
| **上行**（设备 → 平台） | 6 | 心跳、位置、环境传感、影像元数据、告警、任务回执 |
| **下行**（平台 → 设备） | 1 | 任务指令 |

**但 Kafka 在本项目的价值不止于"传消息"，更在于三点：**

**① 解耦设备端与平台端。** 设备不直连业务数据库、平台也不持有设备接口，两端各自只持有 Kafka 客户端。（注：影像大文件另走**文件通道**由设备直传存储，与消息通道分离，详见《[../架构分析/影像文件通道设计.md](../架构分析/影像文件通道设计.md)》。）

**② 切断依赖环。** 业务上，上报链与下发链合起来构成一个闭环；但在**代码依赖**上，两端互不持有对方接口，因此不构成循环依赖。

```text
若把"任务下发"做成业务服务同步 HTTP 直调设备端：
    业务服务 ──依赖──→ 设备接口 ──回调──→ 业务服务     ← 真正的依赖环
本项目的做法：
    设备仿真 ──→ Kafka ──→ 业务服务；业务服务 ──→ Kafka ──→ 设备仿真
    两端都只指向 Kafka，无回指                        ← 无环有向图
```

**③ 削峰填谷。** 设备暴增时，消息先堆积在 Kafka 中，平台按自身能力消费，不会立即被压垮。

---

## 二、实现方式

### 2.1 主题规划

**表 2-1 主题设计表**

| # | 主题名 | 方向 | 消息内容 | 分区数 | 可靠性 |
| :--- | :--- | :--- | :--- | ---: | :--- |
| 1 | `topic_device_heartbeat` | 上行 | 心跳（电量、状态） | 1 | 中 |
| 2 | `topic_device_gps` | 上行 | 位置坐标 | 1 | 低 |
| 3 | `topic_device_sensor` | 上行 | 环境传感读数 | 1 | 中 |
| 4 | `topic_device_media_meta` | 上行 | 影像元数据 | 1 | 中 |
| 5 | `topic_device_alarm` | 上行 | 告警事件 | 1 | **高** |
| 6 | `topic_device_task_ack` | 上行 | 任务执行回执 | 1 | **高** |
| 7 | `topic_platform_command` | **下行** | 任务指令 | 3 | **高** |

**主题名集中管理**，避免字符串散落各处：

```java
public final class Topics {
    // 上行：设备 → 平台
    public static final String DEVICE_HEARTBEAT  = "topic_device_heartbeat";
    public static final String DEVICE_GPS        = "topic_device_gps";
    public static final String DEVICE_SENSOR     = "topic_device_sensor";
    public static final String DEVICE_MEDIA_META = "topic_device_media_meta";
    public static final String DEVICE_ALARM      = "topic_device_alarm";
    public static final String DEVICE_TASK_ACK   = "topic_device_task_ack";
    // 下行：平台 → 设备
    public static final String PLATFORM_COMMAND  = "topic_platform_command";
}
```

### 2.2 技术选择：Spring Kafka

使用 `spring-boot-starter-kafka`，生产端用 `KafkaTemplate`，消费端用 `@KafkaListener` 注解，由 Spring 管理容器生命周期。

---

## 三、步骤与代码

### 步骤 1：部署 Kafka 容器（KRaft 模式 + 双 listener）

```yaml
# docker-compose.yml
kafka:
  image: apache/kafka:4.3.1
  container_name: kafka
  ports:
    - "19092:19092"          # 宿主机程序（仿真端）用这个
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller          # KRaft：单节点同时担任两种角色
    # 双 listener：容器内用 kafka:9092，宿主机用 localhost:19092
    KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:19092
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:19092
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
    KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
    KAFKA_LOG_DIRS: /var/lib/kafka/data
  volumes:
    - kafka-data:/var/lib/kafka/data
```

**KRaft 模式的意义**：Kafka 3.3 起引入 KRaft（Kafka Raft），以内部 Raft 协议替代 ZooKeeper 完成集群元数据管理。本项目采用单节点 KRaft，**减少了一个组件的部署与运维**。

### 步骤 2：配置连接

```properties
# application.properties（平台服务，本地开发）
spring.kafka.bootstrap-servers=localhost:19092
spring.kafka.consumer.group-id=platform-service-group
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.listener.missing-topics-fatal=false      # 主题不存在时不阻断启动
```

```properties
# application.properties（仿真端）
spring.kafka.bootstrap-servers=localhost:19092
spring.kafka.consumer.group-id=device-simulator-group
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.listener.missing-topics-fatal=false
```

> **`missing-topics-fatal=false` 的作用**：默认情况下，订阅的主题不存在会导致应用启动失败。设为 false 后，应用可正常启动并等待主题被创建。

### 步骤 3：生产者（平台侧统一出口）

```java
@Component
public class MessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    /**
     * @param topic   目标主题（用 Topics 常量）
     * @param key     分区键（用 deviceNo，保证同一设备消息有序）
     * @param payload 消息体（record 对象，自动序列化为 JSON）
     */
    public void publish(String topic, String key, Object payload) {
        try {
            String json = jsonMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, key, json);         // ← 第二个参数是分区键
            log.info("[MQ] → {} | key={}", topic, key);
        } catch (Exception e) {
            log.error("[MQ] 发送失败 {}: {}", topic, e.getMessage());
            throw new IllegalStateException("消息发送失败: " + topic, e);
        }
    }
}
```

**设计意图**：把"序列化 + 发送 + 日志 + 异常处理"收敛到一处，业务代码只需说明"发到哪个主题、用什么键、发什么对象"。

### 步骤 4：消费者（入站适配器）

平台侧共 6 个消费者类，每个监听一个上行主题：

```java
@Component
public class HeartbeatConsumer {

    private final JsonMapper jsonMapper;
    private final DeviceService deviceService;

    @KafkaListener(topics = Topics.DEVICE_HEARTBEAT)
    public void onHeartbeat(String message) {
        try {
            HeartbeatMessage hb = jsonMapper.readValue(message, HeartbeatMessage.class);
            deviceService.handleHeartbeat(hb);
        } catch (Exception e) {
            log.error("[心跳消费] 处理失败: {}", e.getMessage());
        }
    }
}
```

**设备侧的下行消费者**额外承担"按设备路由"的职责：

```java
@Component
public class CommandConsumer {

    private final DeviceManager deviceManager;

    @KafkaListener(topics = DeviceSimulator.TOPIC_COMMAND)
    public void onCommand(String message) {
        try {
            CommandMessage cmd = MAPPER.readValue(message, CommandMessage.class);

            DeviceSimulator device = deviceManager.find(cmd.deviceNo());   // ← 按设备编号路由
            if (device == null) {
                log.warn("[指令消费者] 未找到设备 {}，指令忽略", cmd.deviceNo());
                return;
            }
            device.handleCommand(cmd);        // 交给具体设备去"执行并回执"
        } catch (Exception e) {
            log.error("[指令消费者] 指令处理失败: {}", e.getMessage());
        }
    }
}
```

**设计要点**：消费者只做"协议转换 + 路由"，业务逻辑全部下沉到 Service 层。这样消费逻辑与业务逻辑可独立测试，且更换消息中间件时只需改造消费者。

### 步骤 5：海量数据的攒批写入

消费者收到消息后，对高频遥测数据采用"攒批 + 超时兜底"模式写入数据库：

```java
private static final int BATCH_SIZE = 100;        // 攒够 100 条写一次
private final List<TelemetryDoc> buffer = new ArrayList<>(BATCH_SIZE);

public synchronized void add(GpsMessage gps) {
    buffer.add(toDoc(gps));
    if (buffer.size() >= BATCH_SIZE) {
        flush();
    }
}

/** 定时兜底：每 2 秒把缓冲区剩余数据写掉，避免低峰期长期不落库 */
@Scheduled(fixedRate = 2000)
public synchronized void flushByTime() {
    flush();
}

private void flush() {
    if (buffer.isEmpty()) return;
    int size = buffer.size();
    mongoTemplate.insert(buffer, TelemetryDoc.class);   // 一次往返写 N 条
    buffer.clear();
}
```

> **注意**：严格说这一层属于"消费之后的数据处理"，不属于 Kafka 本身。但因为它直接服务于消息消费链路，故在此一并说明。详见《MongoDB 实现说明》。

### 步骤 6：指令下发（平台 → 设备）

```java
// TaskService.dispatch()
String msgId = "CMD-" + task.getTaskId() + "-" + count;      // 重发时重新生成

CommandMessage command = new CommandMessage(
        msgId, task.getTaskId(), task.getDeviceNo(), task.getTaskType(),
        task.getArea(), task.getRoute(), now);

messagePublisher.publish(Topics.PLATFORM_COMMAND, task.getDeviceNo(), command);
```

---

## 四、设计决策

### 决策 1：为什么选 Kafka 而非 RocketMQ

| 维度 | Kafka（选定） | RocketMQ（备选） |
| :--- | :--- | :--- |
| 吞吐量 | 极高，分布式日志式设计 | 高 |
| 持久化与顺序 | 分区内有序、磁盘顺序写 | 支持消息轨迹 / 延迟 / 事务消息 |
| 生态与资料 | Apache 生态，与 HDFS / ES 协作资料多 | 阿里系，中文文档友好 |
| 部署形态 | 依赖 ZooKeeper（KRaft 可免除） | 内置 NameServer |

**选定理由**：① 设备高频心跳与巡检数据上报是典型**高吞吐流式数据**场景，契合 Kafka 设计目标；② 消息持久化与分区内有序能支撑"上报不丢失、指令按序"；③ 与 HDFS、Elasticsearch 同属 Apache 大数据生态；④ KRaft 免去 ZooKeeper 依赖，简化容器部署。

### 决策 2：为什么用 `deviceNo` 作分区键 ★

这是本项目最重要的一个设计决策。

**Kafka 只保证分区内有序，不保证分区之间有序。** 因此：

```java
kafkaTemplate.send(topic, deviceNo, json);     // ↑ 第二个参数 = 分区键
```

以设备编号作 Key 后，Kafka 依据 Key 的哈希值将其路由到**固定分区**，从而保证**同一设备的消息按序处理**。

**为什么需要同源有序**：设备的"位置 → 传感 → 告警"之间存在因果顺序，若乱序处理，可能出现"告警先于触发它的传感读数"这类逻辑异常。

**这个决策的代价**：它**限制了分区数的提升空间**——若设备数量增大，热门设备会撑爆单个分区（详见第六章与决策 5）。

### 决策 3：为什么配置双 listener

**问题**：Kafka 的**广告监听器（advertised.listeners）** 机制决定了——客户端首次连接后，Broker 会返回"对外广播的地址"，客户端后续按此地址重连。

若只配置容器内地址 `kafka:9092`，宿主机上的仿真端收到该地址后无法解析，连接失败。这就是常说的 Kafka "**门牌号**"问题。

**解决**：配置双 listener，区分两个访问入口：

| Listener | 地址 | 使用者 |
| :--- | :--- | :--- |
| `PLAINTEXT` | `kafka:9092` | 容器内的业务服务 |
| `EXTERNAL` | `localhost:19092` | 宿主机上的仿真端 |

```yaml
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:19092
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:19092
```

### 决策 4：可靠性按数据类型分级

不同数据的可靠性要求不同，不应"一刀切"：

| 数据 | 可靠性 | 理由 |
| :--- | :--- | :--- |
| 告警、任务回执 | **高** | 丢失会导致业务中断或状态不一致 |
| 心跳、传感 | 中 | 偶发丢失可由后续数据补偿 |
| 位置轨迹 | 低 | 高频上报，单条丢失无影响 |

这一分级体现在主题划分与生产者参数配置上。

### 决策 5：分区数与消费并行度 ★ 本项目的关键发现

**容量压测实验中发现的核心约束**：

```text
$ kafka-topics.sh --describe
Topic: topic_device_gps    PartitionCount: 1   ReplicationFactor: 1
Topic: topic_device_alarm  PartitionCount: 1   ReplicationFactor: 1
……（全部 7 个业务主题均为 1 个分区）
```

**含义**：

> Kafka 的**并行单位是分区**，不是消费者。一个分区在同一时刻只能被同一消费者组内的一个消费者消费。
>
> 因此：**消费并行度上限 = 分区数 = 1**

**由此得出三个推论**：

1. 后端每个主题只有 1 个消费线程真正工作；
2. **该 topic 的消费能力不会提升**——即使起了第 2 个后端实例，1 个分区也只能被 1 个消费者消费。
   （但需注意：**不同 topic 会分散到不同实例**，进程级负载确实被分担了，只是单个 topic 的吞吐上不去。二者不要混淆。）
3. **这是系统吞吐的硬天花板，无法通过增加 CPU 或内存突破。**

**这是经过权衡的设计，而非单纯的疏忽**：系统需要保证"同一设备消息有序"，而这依赖于以设备编号作分区键（同键路由至同一分区）。因此分区数不能随意增大——**在"顺序性"与"吞吐"之间需要取舍**。

**可行的优化方向**：

> 以 `deviceNo + 时间窗` 作复合键（如 `DOG-001#2026091810`），将同一设备的消息分散到 N 个分区，同时把消费并发度同步提高到 N。
>
> **代价是牺牲"同设备全序"，换取分区均衡**。这是一个明确的取舍，不是免费的性能提升。
>
> 由于代码中已经使用 `deviceNo` 作为分区键，该优化**只需调整配置，无需修改业务代码**。

---

## 五、原理层面

### 5.1 核心抽象

| 概念 | 含义 |
| :--- | :--- |
| **Topic** | 消息的逻辑分类 |
| **Partition** | 主题的物理分片，**是 Kafka 的并行基本单位** |
| **Offset** | 消息在分区内的唯一递增编号 |
| **Producer** | 消息发布方 |
| **Consumer Group** | 一组协同消费的消费者；组内每个分区只被一个消费者消费 |
| **Broker** | Kafka 服务实例 |
| **Replica** | 分区的副本，提供高可用 |

### 5.2 三个关键语义

**（1）分区是并行的上限。** 一个分区只能被一个消费者消费，因此并行度由分区数决定。

**（2）分区内有序，分区间无序。** 这是"用 `deviceNo` 作分区键"的理论依据。

**（3）持久化与副本。** Kafka 将消息顺序写入磁盘以获得高吞吐，并支持分区级副本。副本数与最小同步副本数（`min.insync.replicas`）共同决定可靠性等级。

### 5.3 KRaft 模式

以内部 Raft 协议替代 ZooKeeper 管理集群元数据。本项目的单节点 KRaft 部署同时承担 `broker` 与 `controller` 两个角色。

### 5.4 为什么 Kafka 吞吐高

| 机制 | 作用 |
| :--- | :--- |
| **顺序写磁盘** | 顺序 I/O 远快于随机 I/O |
| **页缓存（Page Cache）** | 读写走操作系统页缓存，避免重复读盘 |
| **零拷贝（sendfile）** | 数据从页缓存直接送到网卡，跳过用户态拷贝 |
| **批量与压缩** | 生产者攒批发送，显著减少网络请求次数 |
| **分区并行** | 多分区可并行读写 |

---

## 六、当前不足与优化方向

| # | 不足 | 影响 | 优化方向 |
| :--- | :--- | :--- | :--- |
| 1 | **所有上行主题仅 1 个分区** | 消费并行度被物理限制为 1，**这是系统吞吐的硬天花板** | 提高分区数 + 同步提高消费并发度（见决策 5） |
| 2 | **单 Broker、副本数 1** | 无高可用能力，Broker 故障即服务中断 | 部署 3+ Broker，副本数设为 3 |
| 3 | **无死信队列** | 处理失败的消息会反复重试，可能阻塞整个分区 | 配置错误处理器，失败消息转投死信队列 |
| 4 | **生产端未配置批量参数** | `linger.ms=0` + 不压缩，一条消息一次请求 | 配置 `linger.ms` 与 `compression.type` |
| 5 | **设备侧发送为 fire-and-forget** | `send()` 返回值被丢弃，发送失败无法感知 | 处理返回的 `CompletableFuture`，记录失败 |
| 6 | **消费端为单线程** | 未配置 `concurrency`（默认 1） | 提高分区数后同步配置并发度 |
| 7 | **无消息轨迹与监控** | 消息流转过程不可观测 | 引入 Kafka 监控或链路追踪 |

---

## 附：一页速记

```text
【作用】中枢神经：6 个上行主题 + 1 个下行主题；解耦设备与平台、切断依赖环、削峰填谷
【方式】Spring Kafka（KafkaTemplate + @KafkaListener）
【关键配置】
    · KRaft 模式（免 ZooKeeper）：PROCESS_ROLES=broker,controller
    · 双 listener：PLAINTEXT://kafka:9092（容器内） / EXTERNAL://localhost:19092（宿主机）
    · 分区键 = deviceNo → 同一设备消息路由至固定分区 → 保证同源有序
【消费模式】攒批（BATCH_SIZE）+ @Scheduled 超时兜底 → 一次网络往返写 N 条
【★ 核心约束】
    并行度上限 = 分区数。当前全部主题分区数为 1
    → 消费并行度被锁死为 1
    → 单个 topic 的消费能力上不去（不同 topic 仍可分到不同实例，但单 topic 无提升）
    → 这是硬天花板，加 CPU / 内存无效
【优化取舍】
    改为 deviceNo + 时间窗 复合键 + 提高分区数与并发度
    代价：牺牲"同设备全序"，换取分区均衡
```
