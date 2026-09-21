# Kafka 性能测试手册

> **配套脚本**：`deploy/test/kafka-bench.ps1`（自动化执行本手册的实验）
> **配套文档**：《[性能压测实验手册.md](性能压测实验手册.md)》（系统级压测）、《[容量评估报告.md](容量评估报告.md)》（结论）

---

## 〇、这份手册的特点

| 特性 | 说明 |
| :--- | :--- |
| **可执行** | 每条命令可直接复制粘贴运行，无需改动 |
| **可移植** | 全部通过 `docker exec` 在容器内执行，**宿主机不需要装任何 Kafka 工具**；容器名、地址、主题名全部可配 |
| **不破坏数据** | 测试使用**专用 bench 主题**，不触碰业务数据 |
| **自带解释** | 每项数据都说明"怎么读、什么范围正常、异常意味着什么" |
| **结果可落盘** | `-OutFile bench.csv` 把每次测量写入 CSV —— **不用手工抄数**，可直接填表、直接画图 |
| **批量对照** | `-Sweep` 一次跑完 1/3/6 分区并自动输出对照表 |

**迁移到其他环境**只需改三个参数：

```powershell
.\kafka-bench.ps1 -Container <你的kafka容器名> -Bootstrap <容器内地址:端口> -Topic <测试主题>
```

---

## 一、先搞懂：测 Kafka 到底在测什么

### 1.1 三个层面，别混为一谈

| 层面 | 测什么 | 典型工具 |
| :--- | :--- | :--- |
| **生产端** | 消息**写进** Kafka 有多快 | `kafka-producer-perf-test.sh` |
| **消费端** | 消息**读出来**有多快 | `kafka-consumer-perf-test.sh` |
| **链路端到端** | 从生产到被业务消费完，**积压多少** | `kafka-consumer-groups.sh`（LAG） |

**关键认知**：**生产快 ≠ 系统健康**。Kafka 写入几乎总是很快（顺序写磁盘），
真正会出问题的是**下游消费跟不上**——这时消息不会丢，而是**堆在 Kafka 里（LAG 增长）**。

### 1.2 为什么 LAG 是最重要的指标 ★

```text
LAG = 消息进入 Kafka 的速度  －  消费者消费掉的速度
    = LOG-END-OFFSET        －  CURRENT-OFFSET
```

| 观察 | 含义 |
| :--- | :--- |
| **LAG 稳定为 0** | 消费完全跟得上，**没到瓶颈** |
| LAG 涨了但能回落 | 处于临界区，短时突发还能消化 |
| **LAG 单调增长不回落** | **到顶了** —— 此刻的速率就是当前配置的上限 |
| LAG 持续增长直到磁盘写满 | 最坏情况：消费完全停滞 |

> **为什么不看 CPU**：CPU 打满不代表到顶（可能还有余量）；CPU 没满也不代表健康
> （消费线程可能卡在等下游响应）。**LAG 增长才是"真的处理不过来"的直接证据。**

### 1.3 决定 Kafka 性能的六个参数

| 参数 | 影响 | 位置 |
| :--- | :--- | :--- |
| **分区数** ★ | **消费并行度的物理上限**（一个分区同时只能被一个消费者消费） | 建主题时 |
| **副本数** | 副本越多写入越慢（需等 ISR 确认） | 建主题时 |
| `acks` | `all` 最安全但最慢，`1` 折中，`0` 最快但可能丢 | 生产者 |
| `linger.ms` | 攒批等待时间，**增大可显著提升吞吐**（用延迟换吞吐） | 生产者 |
| `batch.size` | 单批字节数上限 | 生产者 |
| `compression.type` | 压缩算法，**网络带宽受限时有奇效** | 生产者 |

---

## 二、工具：Kafka 自带的脚本（无需安装）

**第一步：确认你的容器里有哪些脚本**（不同版本名称可能略有差异）

```powershell
docker exec kafka ls /opt/kafka/bin | findstr /I "perf offset consumer-groups topics"
```

| 脚本 | 用途 | 关键输出 |
| :--- | :--- | :--- |
| `kafka-topics.sh --describe` | 查看**分区数、副本数** | `PartitionCount: N` |
| `kafka-get-offsets.sh` | 查看各分区**当前偏移量** | `topic:partition:offset` |
| **`kafka-consumer-groups.sh --describe`** | **查看 LAG** ★ | `CURRENT-OFFSET` / `LOG-END-OFFSET` / `LAG` |
| `kafka-producer-perf-test.sh` | **生产端压测** | `records/sec` + 延迟分位 |
| `kafka-consumer-perf-test.sh` | **消费端压测** | `nMsg.sec` / `MB.sec` |
| `kafka-log-dirs.sh` | 查看**磁盘占用** | 各分区日志大小 |

> **Git Bash 用户注意**：容器内路径会被改写成 Windows 路径，需先执行 `export MSYS_NO_PATHCONV=1`。
> **PowerShell 用户无需处理**。

---

## 三、观测 LAG：最核心的操作

### 3.1 一次性查看

```powershell
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh `
  --bootstrap-server localhost:9092 --describe --group platform-service-group
```

**输出示例与读法**：

```text
GROUP                   TOPIC                PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG   CONSUMER-ID
platform-service-group  topic_device_gps     0          20776           20776           0     consumer-...
platform-service-group  topic_device_sensor  0          8269            8269            0     consumer-...
                                                              ↑               ↑         ↑
                                                        已消费到哪      消息写到哪   差多少
```

| 字段 | 含义 |
| :--- | :--- |
| `CURRENT-OFFSET` | 消费组**已确认消费**到第几条 |
| `LOG-END-OFFSET` | 该分区**当前最新**是第几条 |
| **`LAG`** | 两者之差 —— **待处理的消息数** |
| `CONSUMER-ID` | 是哪个消费者实例在消费该分区（**为空说明没有消费者**） |

### 3.2 持续观察（看趋势）

```powershell
# 方式一：用配套脚本（推荐，带颜色与变化量）
cd "D:\school\all\项目\企业项目实践\deploy\test"
.\kafka-bench.ps1 -WatchLag -Topic topic_device_gps -Group platform-service-group

# 方式二：手工循环
while ($true) {
  $o = docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh `
        --bootstrap-server localhost:9092 --describe --group platform-service-group |
        Select-String "topic_device_gps"
  "$(Get-Date -Format 'HH:mm:ss')  $o"
  Start-Sleep -Seconds 5
}
```

### 3.3 怎么读 LAG 曲线

```text
LAG
 │
 │        ╱╱╱╱╱╱╱╱╱╱╱     ← A. 单调增长：消费跟不上，到顶了
 │      ╱
 │    ╱
 └────────────────────→ 时间

 │      ╱╲
 │    ╱    ╲___          ← B. 涨了能回落：临界区，短时突发可消化
 │  ╱
 └────────────────────→ 时间

 │
 │  ────────────────     ← C. 恒为 0：健康，还有余量
 └────────────────────→ 时间
```

| 曲线 | 结论 |
| :--- | :--- |
| **A** | **已到瓶颈**。此时的生产速率 = 系统的消费上限 |
| B | 处于临界区，峰值会积压但能追平 |
| C | 未到瓶颈，可继续加压寻找真正的上限 |

---

## 四、实验设计（用配套脚本一键执行）

### 实验 A：基线采集

```powershell
.\kafka-bench.ps1 -Show
```

**得到**：每个主题的分区数 + 各消费组当前 LAG。

**先看这一项的原因**：**分区数是消费并行度的物理上限**。若发现某主题分区数为 1，那么后面无论怎么调优，**单个 topic 的消费能力都不可能提升**。

---

### 实验 B：生产端吞吐上限 ★

```powershell
.\kafka-bench.ps1 -Recreate -Records 200000 -RecordSize 300
```

**原理**：不限速地向 Kafka 灌消息，测"写进去"能有多快。

**输出示例**：

```text
200000 records sent, 85632.123456 records/sec (24.49 MB/sec),
    145.32 ms avg latency, 892.00 ms max latency,
    8 ms 50th, 32 ms 95th, 96 ms 99th, 350 ms 99.9th.
```

| 数据 | 含义 |
| :--- | :--- |
| `records/sec` | **生产吞吐**（条/秒）—— 核心指标 |
| `MB/sec` | 带宽（MB/秒） |
| `avg latency` | 平均写入延迟 |
| `50th / 95th / 99th / 99.9th` | **延迟分位数** —— 比平均值更能反映真实体验 |

> **分位数怎么读**：若 `50th=8ms` 而 `99th=96ms`，说明**大多数请求很快，但 1% 的请求慢了一个数量级**。
> 平均值会把这个问题掩盖掉 —— 这就是为什么**看 p95/p99 而不是只看平均值**。

---

### 实验 C：消费端吞吐上限 ★

```powershell
.\kafka-bench.ps1 -Recreate -Records 200000
# 脚本会自动执行消费端测试
```

> ⚠️ **注意**：`kafka-consumer-perf-test.sh` 会**真的消费掉数据**（用的是独立消费组，
> 不影响业务消费组，但 bench 主题的数据会被读走）。这也是脚本用专用主题的原因。

**输出示例与读法**：

```text
start.time, end.time, data.consumed.in.MB, MB.sec, data.consumed.in.nMsg, nMsg.sec, ...
2026-09-21 10:00:00, 2026-09-21 10:00:23, 57.22, 2.48, 200000, 8695.65, ...
                                                    ↑        ↑
                                                 带宽       消费吞吐（条/秒）
```

---

### 实验 D：LAG 的产生与回落 ★★

**这是最贴近真实故障场景的实验**。

```powershell
# ① 停掉消费者（模拟后端宕机 / 消费停滞）
docker compose stop platform-service-1 platform-service-2

# ② 另开窗口持续观察 LAG
.\kafka-bench.ps1 -WatchLag -Topic topic_device_gps -Group platform-service-group

# ③ 启动仿真端灌数据（或用 perf-test 灌）
#    —— 此时 LAG 会持续增长，观察增长速率

# ④ 重新启动消费者，观察追赶
docker compose start platform-service-1 platform-service-2
#    —— LAG 应从峰值回落，记录「追平耗时」
```

**能得到的数据**：

| 数据 | 怎么算 | 含义 |
| :--- | :--- | :--- |
| **积压峰值** | 停消费期间 LAG 的最大值 | 宕机期间累积了多少未处理消息 |
| **积压增长速率** | ΔLAG / Δt | 等于**生产速率**（消费为 0 时） |
| **追赶速率** | 积压峰值 ÷ 追平耗时 | **等于消费端的实际处理能力** ★ |
| **追平耗时** | 从重启到 LAG 归零的时间 | 宕机后多久能恢复正常 |

> **追赶速率是这张表里最有价值的数字** —— 它直接给出了"**这个消费组每秒最多能处理多少条**"。
> 注意它通常**低于**实验 C 测出的消费吞吐上限，因为追赶时消费者同时在处理新到达的消息。

---

### 实验 E：分区数对照 ★★★

**这个实验能证明一条关键的架构约束**。

**一条命令跑完全部对照**：

```powershell
.\kafka-bench.ps1 -Sweep -Records 200000 -OutFile sweep.csv
```

脚本会自动依次以 **1 / 3 / 6 分区**重建主题、跑生产与消费测试，最后输出对照表：

```text
分区数    生产(条/秒)   生产(MB/s)     p95(ms)    消费(条/秒)   消费(MB/s)
------  ------------  ----------  ---------  ------------  ----------
     1         85000       24.30         32          8600        2.45
     3         84200       24.10         35         25500        7.30
     6         83800       23.95         38         48900       14.00
```

> 想换档位：`.\kafka-bench.ps1 -Sweep -SweepPartitions 1,3,6,12`

**如果只想手工逐档跑**（便于观察每档的过程）：

```powershell
.\kafka-bench.ps1 -Partitions 1 -Recreate -Records 200000
.\kafka-bench.ps1 -Partitions 3 -Recreate -Records 200000
.\kafka-bench.ps1 -Partitions 6 -Recreate -Records 200000
```

**得到的对照表**：

| 分区数 | 生产吞吐 | 消费吞吐 | 说明 |
| ---: | ---: | ---: | :--- |
| 1 | | | 消费并行度上限 = **1** |
| 3 | | | 上限 = 3 |
| 6 | | | 上限 = 6 |

**预期现象**：

| 观察 | 结论 |
| :--- | :--- |
| 生产吞吐**几乎不变** | Kafka 写入靠顺序追加，分区数对**生产**影响有限 |
| 消费吞吐**随分区数近似线性增长** | **分区数 = 消费并行度的硬上限** ★ |

> **这条结论的价值**：它解释了"为什么加后端实例、加 CPU 都没用"——
> **并行度受限于分区数，而不是机器性能**。
>
> **注意**：仅提高分区数还不够，**消费端的 `concurrency` 必须同步提高**，
> 否则消费者线程数还是 1，多出来的分区没人消费。

---

### 实验 F：生产者参数对照

```powershell
# 基线（默认参数）
.\kafka-bench.ps1 -Recreate -Records 200000

# 攒批 + 压缩
.\kafka-bench.ps1 -Recreate -Records 200000 -LingerMs 20 -Compression lz4

# 降低可靠性要求
.\kafka-bench.ps1 -Recreate -Records 200000 -Acks 1
```

**预期**：`linger.ms=20 + lz4` 能显著提升吞吐与降低带宽；`acks=1` 也比 `acks=all` 快。

> **取舍**：`linger.ms` 增大是用**延迟**换**吞吐**；`acks=1` 是用**可靠性**换**吞吐**。
> 没有免费的午餐，选哪个取决于业务能容忍什么。

---

## 五、数据含义速查表

| 指标 | 从哪来 | 含义 | 正常 | 异常说明什么 |
| :--- | :--- | :--- | :--- | :--- |
| **LAG** | `consumer-groups --describe` | 待处理消息数 | **0** | 单调增长 = 消费到顶 |
| **records/sec** | `producer-perf-test` | 生产吞吐 | 与硬件/分区相关 | 低 = 生产者参数未优化/网络受限 |
| **nMsg.sec** | `consumer-perf-test` | 消费吞吐 | 受分区数限制 | 低 = 分区数不足或消费逻辑重 |
| **p95 / p99** | `producer-perf-test` | 延迟分位 | 与均值同量级 | 远高于均值 = 存在长尾（GC/排队） |
| **avg latency** | `producer-perf-test` | 平均写入延迟 | 与 `acks` 相关 | 高 = 副本同步慢或磁盘瓶颈 |
| **PartitionCount** | `topics --describe` | 分区数 | 按并发需求设定 | **=1 时消费并行度锁死为 1** |
| **ReplicationFactor** | `topics --describe` | 副本数 | 生产建议 3 | =1 无容错 |
| **CONSUMER-ID 为空** | `consumer-groups --describe` | 该分区无消费者 | 应有值 | 为空 = 消费者掉线或分区无人消费 |
| **LOG-END-OFFSET 不涨** | `get-offsets` | 无新消息写入 | 应持续增长 | 不涨 = 生产者停了 |

---

## 六、可移植性：怎么用到别的环境

### 6.1 只需改三个参数

```powershell
.\kafka-bench.ps1 -Container kafka -Bootstrap localhost:9092 -Topic bench-topic
```

| 参数 | 说明 |
| :--- | :--- |
| `-Container` | Kafka 容器名（`docker ps` 查） |
| `-Bootstrap` | **容器内**的地址端口，不是宿主机映射端口 |
| `-Topic` | 测试用主题名（会自动创建） |

### 6.2 换成 Linux / 非 Docker 环境

脚本的核心是 `docker exec <容器> <kafka脚本>`。若 Kafka 直接装在宿主机上：

```bash
# Docker 环境
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe

# 宿主机直装环境：去掉 docker exec 前缀，路径换成实际安装路径
/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
```

**其余参数完全一致** —— 因为 Kafka 自带脚本的接口是统一的。

---

## 七、与本项目的结合

本项目的业务主题需要观测 LAG 时，用业务消费组而非 bench 组：

```powershell
# 观察全部业务主题的 LAG
cd "D:\school\all\项目\企业项目实践\deploy\test"
.\kafka-bench.ps1 -Show                              # 分区布局 + LAG

# 持续观察某个业务主题
.\kafka-bench.ps1 -WatchLag -Topic topic_device_gps -Group platform-service-group
```

**本项目已测得的结论**（详见《[容量评估报告.md](容量评估报告.md)》）：

| 项 | 值 |
| :--- | :--- |
| 业务主题分区数 | 改造前 **1** → 改造后 **3** |
| 消费并发度 | 同步设为 **3** |
| 6 台设备消息速率 | 约 **7 条/秒** |
| 稳态 LAG | **0**（后端完全跟得上） |

> **一句话**：本项目的 Kafka **远未到瓶颈**，LAG 恒为 0；
> 真正的约束是**分区数决定了消费并行度的上限**，这才是需要关注的架构参数。

---

## 八、记录表模板

### 8.1 CSV 自动落盘（推荐）

加 `-OutFile` 参数后，**每次测量自动追加一行**到 CSV，不用手工抄数：

```powershell
.\kafka-bench.ps1 -Sweep -Records 200000 -OutFile sweep.csv
```

**CSV 字段说明**：

| 字段 | 含义 |
| :--- | :--- |
| `timestamp` | 测量时刻 |
| `partitions` | 主题分区数 |
| `records` / `record_size` | 发送条数 / 消息大小（字节） |
| `acks` / `linger_ms` / `compression` | 生产者参数（对照用） |
| `produce_rec_per_sec` | **生产吞吐**（条/秒） |
| `produce_mb_per_sec` | 生产带宽（MB/s） |
| `produce_avg_ms` | 平均写入延迟 |
| `produce_p50_ms` / `p95_ms` / `p99_ms` | **延迟分位数** |
| `consume_rec_per_sec` | **消费吞吐**（条/秒） |
| `consume_mb_per_sec` | 消费带宽（MB/s） |

**这个 CSV 直接可用**：
- 填进下面的记录表
- 用 Excel 画「分区数 vs 消费吞吐」的曲线
- 作为原始数据附在课程报告里

### 8.2 手工记录表

**实验日期**：＿＿＿＿　**环境**：＿＿＿＿　**分区数**：＿＿＿＿

| 指标 | 实测值 | 备注 |
| :--- | ---: | :--- |
| 主题分区数 | | |
| 副本数 | | |
| **生产吞吐 (records/sec)** | | |
| 生产带宽 (MB/sec) | | |
| 生产 p50 延迟 (ms) | | |
| 生产 p95 延迟 (ms) | | |
| 生产 p99 延迟 (ms) | | |
| **消费吞吐 (nMsg/sec)** | | |
| 消费带宽 (MB/sec) | | |
| 稳态 LAG | | |
| 积压峰值 | | |
| **追赶速率 (条/秒)** | | |
| 追平耗时 (秒) | | |

**分区对照表**（由 `-Sweep` 自动生成）：

| 分区数 | 生产吞吐 | 消费吞吐 | 消费/生产 比值 |
| ---: | ---: | ---: | ---: |
| 1 | | | |
| 3 | | | |
| 6 | | | |

**结论**：＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿＿
