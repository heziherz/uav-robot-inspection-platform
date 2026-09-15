# 无人机-机器狗空地协同巡检集成平台

> 《企业应用开发实践》课程项目 · 园区安防空地协同巡检场景
>
> 模拟无人机高空巡查发现线索、机器狗地面抵近复核的巡检业务，通过分布式中间件、分布式存储、检索引擎与负载均衡网关完成企业级系统集成。

---

## 一、业务闭环

```text
无人机（空中）                 机器狗（地面）
高空巡逻、发现可疑目标    →    进入楼道/围墙死角、抵近复核
        │                              ▲
        └──── 数据上报 ──→ 平台 ── 任务下发 ──┘
                            │
                存储 / 检索 / 可视化 / 闭环处置
```

---

## 二、技术栈

| 层次 | 技术 |
| :--- | :--- |
| **设备仿真层** | Java 17 + Spring Boot 4（模拟无人机/机器狗，只与 Kafka 交互） |
| **消息中间件** | Apache Kafka 4.3.1（KRaft 模式，双 listener 适配容器内外） |
| **分布式存储** | Hadoop HDFS 3.2.1（巡检影像大文件）+ MongoDB 8.3.9（结构化数据） |
| **检索引擎** | Elasticsearch 9.5.3 + Kibana（告警检索与可视化） |
| **网关** | Nginx 1.30（静态托管 + API 反向代理） |
| **后端服务** | Java 17 + Spring Boot 4 + Spring Kafka + Spring Data MongoDB |
| **前端** | React 19 + Vite + Ant Design 6 + Leaflet + ECharts |
| **容器化** | Docker Compose（单机模拟集群，8 个容器） |

---

## 三、系统架构（部署视图）

```text
═══════════════ 设备侧（宿主机独立运行）═══════════════
   device-simulator  仿真端
    ├─ 3 台无人机：心跳 / GPS 轨迹 / 航拍影像元数据 / 告警
    └─ 3 台机器狗：心跳 / 位置 / 环境传感器 / 红外影像 / 告警
                          │
                     Kafka（7 个 topic）
                          ▼
═══════════════ 平台侧（Docker Compose 编排）═══════════════
   Nginx(80) ──┬── 前端静态资源（React 构建产物）
               └── /api ──→ platform-service(8080)
                                  │
                    ┌─────────────┼──────────────┬─────────────┐
                    ▼             ▼              ▼             ▼
                MongoDB      Elasticsearch      HDFS        Kafka
              （明细/状态）   （告警索引检索）  （影像文件）  （消费消息）
```

**关键设计**：**同一份代码、两套地址** —— 本地开发用 `localhost`，容器内用服务名（`kafka:9092`、`mongodb:27017`），通过环境变量覆盖。

---

## 四、快速启动

### 前置要求

- **Docker Desktop**（Windows 家庭版需启用 WSL2）
- **JDK 17** + **IDEA**（运行仿真端）
- **Node.js 18+**（仅前端开发时需要）

### 启动步骤

```powershell
# ① 启动平台侧（8 个容器：中间件 + 后端 + 网关）
cd deploy
docker compose up -d
docker compose ps

# ② 构建前端（首次或前端改动后）
cd ../frontend
npm install
npm run build

# ③ 启动设备仿真端
#    在 IDEA 中运行 backend/device-simulator 的 DeviceSimulatorApplication
```

### 访问

| 入口 | 地址 |
| :--- | :--- |
| **平台前端** | <http://localhost> |
| HDFS WebUI | <http://localhost:9870> |
| Kibana | <http://localhost:5601> |

> 详细部署说明与常见问题见 [`deploy/README.md`](deploy/README.md)

---

## 五、功能清单

| 页面 / 功能 | 数据来源 | 对应需求 |
| :--- | :--- | :--- |
| 设备列表（状态、电量、心跳数） | MongoDB | UC-01/02/11 |
| 告警列表（待处理/已处置筛选） | MongoDB | UC-14/15 |
| 设备地图（实时位置，5 秒刷新） | MongoDB + Leaflet | UC-11 |
| 告警检索（类型/级别过滤） | **Elasticsearch** | UC-16 |
| 巡检影像归档 | **HDFS** | UC-03 |
| 任务下发与执行回执 | Kafka 双向 | UC-04/12 |

---

## 六、目录结构

```text
企业项目实践/
├── backend/
│   ├── device-simulator/     设备仿真端（代表外部设备，独立运行）
│   └── platform-service/     平台业务服务（容器化，含 Dockerfile）
├── frontend/                 React 前端（Vite + antd + Leaflet）
├── deploy/                   Docker 编排 + Nginx 配置 + 部署说明
└── doc/                      课程文档
    ├── 需求分析/              需求分析文档、仿真数据与消息契约说明
    └── 架构分析/              架构图详解、需求分析与总体架构技术选型
```

---

## 七、文档索引

| 文档 | 内容 |
| :--- | :--- |
| [`doc/需求分析/需求分析文档.md`](doc/需求分析/需求分析文档.md) | 参与者、用例清单与规格、模块划分 |
| [`doc/需求分析/仿真数据与消息契约说明.md`](doc/需求分析/仿真数据与消息契约说明.md) | 数据分类、字段定义、Topic 契约、存储映射 |
| [`doc/架构分析/需求分析与总体架构技术选型.md`](doc/架构分析/需求分析与总体架构技术选型.md) | 总体架构、技术选型与理由、部署方案 |
| [`doc/架构分析/架构图详解.md`](doc/架构分析/架构图详解.md) | 各类架构图的分类、用法与示例 |
| [`deploy/README.md`](deploy/README.md) | 部署说明、配置说明、常见问题 |
| [`运行指南.md`](运行指南.md) | **日常操作手册**：启动方式、数据链路验证、答辩演示流程、功能完成度、排错 |
| [`组员复现指南.md`](组员复现指南.md) | **在你自己电脑上跑起来**：4 步操作 + 验证清单 + 常见问题 |
