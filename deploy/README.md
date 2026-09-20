# 部署说明 —— 无人机-机器狗空地协同巡检集成平台

> 本目录包含**平台侧**的完整容器编排。一条命令即可启动整个平台。
>
> 配套文档：《需求分析与总体架构技术选型.md》《仿真数据与消息契约说明.md》

---

## 一、部署形态：设备侧 / 平台侧分离

```text
┌───────────────────── 设备侧（独立运行）─────────────────────┐
│   device-simulator（无人机 / 机器狗仿真）                    │
│   · 代表园区里真实的设备，独立部署、通过消息中间件接入          │
│   · 本地开发模式：IDEA 运行（改代码即改即生效）                │
└───────────────────────────┬────────────────────────────────┘
                            │ Kafka
┌───────────────────── 平台侧（Docker 编排）──────────────────┐
│  Nginx 网关 → platform-service（业务服务）                   │
│  Kafka · MongoDB · Elasticsearch · Kibana · HDFS · Nginx    │
│  （Nginx 同时托管 React 前端构建产物）                        │
└────────────────────────────────────────────────────────────┘
```

**为什么仿真端不打包进容器**：它模拟的是**系统外部的设备**，真实场景下设备不会跑在服务器容器里；独立运行既符合架构语义，也便于开发调试（改仿真参数无需重新构建镜像）。

---

## 二、前置要求

| 软件 | 版本 | 是否需要 |
| :--- | :--- | :--- |
| **Docker Desktop** | 最新稳定版 | ✅ 必需（Windows 家庭版需先启用 WSL2） |
| JDK | 17 | 仅在**本地开发模式**下需要（跑仿真端/后端） |
| Node.js | 18+ | 仅在**前端开发**时需要（`npm run dev`） |
| Maven | — | 不需要（后端用 Docker 内的 Maven 构建；本地用 IDEA 内置 Maven） |

---

## 三、一键启动

```powershell
cd deploy
docker compose up -d
docker compose ps
```

> **首次启动较慢**：需要拉取约 5~6GB 镜像（HDFS / ES / Kibana / Kafka 等），国内网络建议配置镜像加速。
> 后端镜像首次构建需从 Maven 仓库下载依赖（已配置阿里云镜像加速），约 3~10 分钟。

**启动后还需要启动仿真端**（设备侧）：

```powershell
cd ../backend/device-simulator
# 在 IDEA 中运行 DeviceSimulatorApplication（点 ▶）
```

---

## 四、容器清单（8 个）

| # | 容器名 | 镜像 | 端口映射 | 角色 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `hadoop-namenode` | bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8 | 9870 / 9000→8020 | HDFS 元数据节点 |
| 2 | `hadoop-datanode` | bde2020/hadoop-datanode:2.0.0-hadoop3.2.1-java8 | 9864 | HDFS 数据节点 |
| 3 | `mongodb` | mongo:8.3.9 | 27017 | 分布式数据库（设备/任务/告警明细） |
| 4 | `kafka` | apache/kafka:4.3.1 | **19092**（宿主机） | 消息中间件（KRaft 模式，免 Zookeeper） |
| 5 | `elasticsearch` | elastic.co/elasticsearch:9.5.3 | 9200 | 全文检索引擎 |
| 6 | `kibana` | elastic.co/kibana:9.5.3 | 5601 | 检索可视化仪表盘 |
| 7 | `platform-service` | 本仓库构建（`backend/platform-service/Dockerfile`） | 8080（**仅容器内**） | 平台业务服务：消息消费 + 存储 + REST 接口 |
| 8 | `nginx-gateway` | nginx:1.30-alpine | 80 | 统一网关：托管前端 + `/api` 反向代理 |

> 前端（React）构建产物通过**卷挂载**交给 Nginx 托管，本身不是一个容器。

---

## 五、访问入口

| 入口 | 地址 | 说明 |
| :--- | :--- | :--- |
| **平台前端** | <http://localhost> | 设备列表 / 告警列表 / 设备地图 / 告警检索（四个页面） |
| **后端 API（经网关）** | <http://localhost/api/devices> | Nginx 反向代理到 platform-service |
| HDFS WebUI | <http://localhost:9870> | 可浏览 `/uav/media/` 下的巡检影像 |
| Kibana | <http://localhost:5601> | 告警数据可视化 |
| Elasticsearch | <http://localhost:9200> | REST API |

### 快速验证

> ⚠️ 业务接口已启用 **JWT 认证**：先登录换取 token，再带 `Authorization` 头调用。

```powershell
# ① 登录（默认账号见下方"默认账号"）
$login = curl.exe -s -X POST http://localhost/api/auth/login -H "Content-Type: application/json" -d '{\"username\":\"admin\",\"password\":\"admin123\"}' | ConvertFrom-Json

# ② 带 token 调用业务接口
curl.exe -s http://localhost/api/devices -H "Authorization: Bearer $($login.token)"
curl.exe -s "http://localhost/api/alarms/search?type=OVERHEAT" -H "Authorization: Bearer $($login.token)"

# ③ ES 直连（检索引擎未加认证）
curl.exe -s "http://localhost:9200/alarm-*/_search?pretty&size=1"
```

### 默认账号（首次启动自动创建）

| 账号 | 密码 | 角色 | 权限 |
| :--- | :--- | :--- | :--- |
| `admin` | `admin123` | 系统管理员 | 全部 + **设备管理** + **用户管理** |
| `operator` | `123456` | 巡检值班员 | 查看、告警处置、检索 |
| `ops` | `123456` | 系统运维人员 | 同上（运维视角） |

---

## 六、配置说明

### 6.1 后端服务的环境变量（`docker-compose.yml` 中 platform-service 段）

| 环境变量 | 容器内取值 | 对应的配置项 |
| :--- | :--- | :--- |
| `SPRING_MONGODB_URI` | `mongodb://mongodb:27017/uav_platform` | `spring.mongodb.uri` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | `spring.kafka.bootstrap-servers` |
| `ES_BASE_URL` | `http://elasticsearch:9200` | `es.base-url` |
| `HDFS_WEBHDFS_BASE` | `http://hadoop-namenode:9870` | `hdfs.webhdfs-base` |

> **说明**：平台侧只用 WebHDFS **读取**影像；影像的**写入由设备自己完成**
> （见 `backend/device-simulator` 的 `StorageClient`），因此平台容器**不再挂载**
> `sim-files` 目录，也没有 `SIM_FILES_DIR` 环境变量。

**设计原则：同一份代码、两套地址** ——
- **本地开发**：用 `application.properties` 里的默认值（`localhost:xxxx`）
- **容器运行**：用环境变量覆盖为**服务名**（Docker 内建 DNS 解析）

> ⚠️ **Spring Boot 4 注意**：MongoDB 连接配置前缀由 `spring.data.mongodb.*` **改为 `spring.mongodb.*`**（环境变量同理）。用旧名字会被**静默忽略**并回退到 `localhost`，非常难排查。

### 6.2 Kafka 的双 listener（关键设计）

```yaml
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:19092
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:19092
```

| 客户端 | 连接地址 | Kafka 回告的地址 |
| :--- | :--- | :--- |
| 容器内（platform-service） | `kafka:9092` | `kafka:9092` ✅ |
| 宿主机（device-simulator） | `localhost:19092` | `localhost:19092` ✅ |

> Kafka 的 `advertised.listeners` 是"**门牌号**"：broker 会把它回告给客户端，让客户端用它去连。容器内外地址不同，所以必须配两组 listener——**这是容器化 Kafka 最容易踩的坑**。

---

## 七、常用运维命令

```powershell
docker compose ps                          # 查看所有容器状态
docker compose logs -f platform-service    # 实时看某个服务日志
docker compose logs --tail=50 nginx        # 看最后 50 行
docker compose stop platform-service       # 只停某个服务
docker compose up -d --build platform-service --force-recreate   # 改代码后重建（见下方说明）
docker stats                               # 实时查看资源占用
```

### 改了代码后如何生效？

| 改动 | 生效方式 |
| :--- | :--- |
| **后端 Java 代码** | `docker compose up -d --build --force-recreate platform-service` |
| **前端 React 代码** | `cd frontend && npm run build`（Nginx 挂载目录自动更新，刷新浏览器即可） |
| **Nginx 配置** | `docker exec nginx-gateway nginx -s reload`（挂载的配置改了不会自动生效！） |
| **docker-compose.yml** | `docker compose up -d` |

> ⚠️ **两个高频坑**：
> 1. `--build` 只重新构建镜像，**容器可能不会自动重建**（仍用旧镜像）→ 必须加 `--force-recreate`
> 2. Nginx 的配置是**挂载**进容器的，改了文件后**必须 reload**

---

## 八、常见问题（实战踩坑记录）

| 现象 | 根因 | 解决 |
| :--- | :--- | :--- |
| 后端连 `localhost:27017` 而非 `mongodb:27017` | **Spring Boot 4 改了 MongoDB 配置前缀** | 用 `spring.mongodb.uri` / `SPRING_MONGODB_URI` |
| 容器内连不上 Kafka | `advertised.listeners` 只配了宿主机地址 | 配双 listener（见 6.2） |
| 改了代码但容器行为没变 | 容器没重建（还在用旧镜像） | 加 `--force-recreate` |
| Nginx 返回 502 | ①后端没起 ②Nginx 配置未 reload | `docker compose ps` 确认后端；`nginx -s reload` |
| ~~HDFS 上传失败，路径形如 `/app/sim-files/D:\...`~~ | ~~Linux 容器认不出 Windows 反斜杠~~ | **已从架构上消除**：影像改由设备自己上传，消息中只传 `storageRef`，平台不再接触设备本地路径。详见《[../doc/架构分析/影像文件通道设计.md](../doc/架构分析/影像文件通道设计.md)》 |
| `Cannot resolve symbol`（Java） | 依赖缺失（如 `spring-kafka` 不含 Jackson） | 查 pom 是否引入对应 starter |
| 镜像拉取极慢/超时 | 国内网络访问 Docker Hub 受限 | 配置镜像加速器 |
| ES 起不来 | 内存不足 | 调小 `ES_JAVA_OPTS` 与 `mem_limit` |
| 前端页面空白 | 前端未构建 / `frontend/dist` 为空 | `cd frontend && npm run build` |

---

## 九、数据卷管理

```powershell
docker compose down          # 停并删容器（数据卷保留）
docker compose down -v       # 连同数据卷一起删（会清空所有数据！）
docker volume ls             # 查看数据卷
```

数据卷清单：`hdfs-namenode`、`hdfs-datanode`、`mongodb-data`、`kafka-data`、`es-data`

> ⚠️ 跨大版本升级中间件（如 ES 8→9）时，数据目录不兼容，需删除对应数据卷重建。
> 只想清某一个，可先 `docker compose rm -sf <服务名>` 再 `docker volume rm deploy_<卷名>`。

---

## 十、目录结构

```text
企业项目实践/
├── backend/
│   ├── device-simulator/     设备仿真端（独立运行，代表外部设备）
│   └── platform-service/     平台业务服务（容器化，含 Dockerfile）
├── frontend/                 React 前端（构建产物由 Nginx 托管）
├── deploy/                   ← 本目录
│   ├── docker-compose.yml    容器编排（9 个容器：6 类中间件 + 2 个后端实例 + Nginx）
│   ├── nginx/conf.d/         网关配置（静态托管 + /api 反向代理）
│   └── README.md             本文档
└── doc/                      需求、架构、技术选型等文档
```
