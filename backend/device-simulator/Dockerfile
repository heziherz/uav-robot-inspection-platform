# ============================================================
# device-simulator 镜像（多阶段构建）
#
# 【架构定位 —— 为什么不并入 platform-service】
#   仿真端代表【系统外部设备】（参与者 A4/A5），逻辑上**不属于平台编排**。
#   容器化只是为了让开发/演示环境能一键启动，**不改变它作为外部系统的定位**。
#
#   因此在 docker-compose.yml 中通过 profiles 控制，默认不启动：
#     docker compose up -d                 → 只起平台侧（边界清晰）
#     docker compose --profile sim up -d   → 连仿真端一起起（演示 / 测试）
#
# 【与 platform-service 镜像的差异】
#   本服务**不是 Web 应用**（不监听端口），进程靠全局调度线程池保活，
#   因此没有 EXPOSE，容器日志即设备控制台输出。
# ============================================================

# ---------- 阶段 1：编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 使用阿里云 Maven 镜像加速
COPY maven-settings.xml /root/.m2/settings.xml

# 先只复制 pom 并预下载依赖（利用层缓存：改代码时不会重新下载依赖）
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再复制源码并打包（跳过测试）
COPY src ./src
RUN mvn -B package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# 注意：不 EXPOSE —— 本服务不是 Web 应用，只通过 Kafka 与平台交互
ENTRYPOINT ["java", "-jar", "app.jar"]
