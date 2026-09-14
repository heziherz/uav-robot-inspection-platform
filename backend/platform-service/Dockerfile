# ============================================================
# platform-service 镜像（多阶段构建）
#   阶段 1：Maven 编译打包    阶段 2：JRE 运行（镜像更小）
# 构建命令（在 deploy 目录）：docker compose up -d --build platform-service
# ============================================================

# ---------- 阶段 1：编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 使用阿里云 Maven 镜像加速（配置文件随源码提供，避免 Dockerfile 里写超长字符串）
COPY maven-settings.xml /root/.m2/settings.xml

# 先只复制 pom 并预下载依赖（利用 Docker 层缓存：改代码时不会重新下载依赖）
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再复制源码并打包（跳过测试）
COPY src ./src
RUN mvn -B package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
