# deploy —— 中间件部署（第 1 步）

本项目全部中间件用 **Docker Compose 单机模拟集群** 部署。镜像版本已固定，规避组件版本兼容问题。

## 一、启动

在 `deploy/` 目录执行：

```bash
cd deploy
docker compose up -d        # 首次会自动拉取镜像，需几分钟
docker compose ps           # 查看状态（STATUS 应为 Up / healthy）
```

> 若 80 / 9000 / 9092 等端口被占用，先 `netstat -ano | findstr :80` 找占用进程，或改 `docker-compose.yml` 里左侧端口号。

## 二、各组件端口与验证

| 组件 | 容器名 | 对外端口 | 访问 / 验证方式 |
| :--- | :--- | :--- | :--- |
| HDFS NameNode | `hadoop-namenode` | WebUI **9870**、RPC 内部 8020 / 外部 **9000** | 浏览器 `http://localhost:9870` |
| HDFS DataNode | `hadoop-datanode` | **9864** | NameNode WebUI 里可见 DataNode 节点 |
| MongoDB | `mongodb` | **27017** | `docker exec -it mongodb mongosh --eval "db.runCommand({ping:1})"` |
| Kafka | `kafka` | **9092** | 见下“建 topic 验证” |
| Elasticsearch | `elasticsearch` | **9200** | `curl http://localhost:9200` 返回带 `"cluster_name"` 的 JSON |
| Kibana | `kibana` | **5601** | 浏览器 `http://localhost:5601`（首次加载较慢） |
| Nginx | `nginx-gateway` | **80** | 浏览器 `http://localhost` 显示平台欢迎页 |

## 三、Kafka 快速验证（建 topic / 看列表）

```bash
# 在容器内执行 Kafka 自带命令（镜像已含 CLI）
docker exec -it kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic test-hello --partitions 1 --replication-factor 1

docker exec -it kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# 应输出: test-hello
```

验证完可删除该测试 topic（可选）：`--delete --topic test-hello`。

## 四、遇到问题排查

| 现象 | 常见原因 / 处理 |
| :--- | :--- |
| `docker compose up` 拉镜像很慢/超时 | 配置 Docker 镜像加速（国内源），或更换网络后重试 |
| ES 起不来 / 反复重启 | 内存不足 → 调小 `ES_JAVA_OPTS` 与 `mem_limit`；检查 `docker logs elasticsearch` |
| Elasticsearch `max virtual memory areas vm.max_map_count` 报错 | 仅在 WSL2/Linux 需要：`wsl -d docker-desktop -e sysctl -w vm.max_map_count=262144`（Docker Desktop 一般已配好，报错再处理） |
| HDFS 一直 Initializing | 首次建 NameNode 较慢；等 1~2 分钟看 `docker logs hadoop-namenode` |
| 端口被占用 | `netstat -ano | findstr :<端口>` 定位后，改 compose 左侧端口 |

## 五、关闭 / 清理

```bash
docker compose down          # 停并删容器（保留数据卷）
docker compose down -v      # 连数据卷一起删（重新初始化用，会清数据！）
```

> 本步骤全部通过后，进入第 2 步：仿真模块 + Kafka 消息生产/消费打通。
