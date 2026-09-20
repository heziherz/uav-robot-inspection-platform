# Nginx 实现说明

> **本文结构**：作用 → 实现方式 → 步骤与代码 → 设计决策 → 原理 → 不足
> **配套**：其他组件见同目录《Elasticsearch 实现说明》《HDFS 实现说明》《Kafka 实现说明》《MongoDB 实现说明》

---

## 一、在项目中做什么

Nginx 是系统**唯一的对外入口**，承担三项职责：

| 职责 | 说明 | 对应用例 |
| :--- | :--- | :--- |
| **静态资源托管** | 直接返回前端构建产物（HTML / CSS / JS） | UC-11 |
| **反向代理** | 将 `/api/` 请求转发至后端业务服务 | 全局 |
| **负载均衡** | `upstream` 定义后端集群，支持多实例分发 | TC-008 |

**请求分流示意**：

```text
浏览器请求
  ├── /              → 返回前端静态资源（React 构建产物）
  └── /api/...       → 反向代理至 platform-service:8080
```

> **注意 Kibana 不走 Nginx**：Kibana 是 Elasticsearch 的**运维分析旁路**，内网直连 ES，端口 5601 单独暴露，不属于 Web 业务访问链路。

---

## 二、实现方式

### 2.1 配置方式：配置文件挂载

Nginx 采用**声明式配置**，本项目以数据卷方式将配置文件挂载进容器：

```yaml
nginx:
  image: nginx:1.30-alpine
  container_name: nginx-gateway
  ports:
    - "80:80"
  volumes:
    - ./nginx/conf.d:/etc/nginx/conf.d:ro          # 网关配置（只读挂载）
    - ../frontend/dist:/usr/share/nginx/html:ro    # 前端构建产物（只读挂载）
```

**两个挂载点的作用**：

| 挂载点 | 容器内路径 | 说明 |
| :--- | :--- | :--- |
| 配置目录 | `/etc/nginx/conf.d` | 自定义的 server 配置 |
| 前端产物 | `/usr/share/nginx/html` | React 构建产物，Nginx 的 `root` 指向此处 |

> **改动生效方式**：由于配置是挂载进去的，修改配置文件后**不需要重启容器**，执行
> `docker exec nginx-gateway nginx -s reload` 即可热加载。

---

## 三、步骤与代码

### 步骤 1：部署容器

```yaml
# docker-compose.yml（见上）
nginx:
  image: nginx:1.30-alpine
  container_name: nginx-gateway
  restart: unless-stopped
  ports:
    - "80:80"
  volumes:
    - ./nginx/conf.d:/etc/nginx/conf.d:ro
    - ../frontend/dist:/usr/share/nginx/html:ro
```

**为什么选 alpine 版本**：镜像体积从约 140 MB 降至约 50 MB。本项目实测 Nginx 容器内存占用仅 **27 MiB**，是 8 个容器中最低的。

### 步骤 2：编写配置

**完整配置**（`deploy/nginx/conf.d/default.conf`）：

```nginx
# ============================================================
#  无人机-机器狗空地协同巡检集成平台 —— Nginx 网关配置
#  职责：① 托管前端静态文件  ② /api 反向代理到后端业务服务集群
# ============================================================

# 后端服务集群
upstream backend_cluster {
    server platform-service:8080;
    # 扩展为多实例时在此追加 server 条目，Nginx 默认轮询分发
    # server platform-service-2:8080;
}

server {
    listen       80;
    server_name  localhost;

    root   /usr/share/nginx/html;
    index  index.html;

    # ---------- 前端静态资源（SPA：找不到的路径回退到 index.html）----------
    location / {
        try_files $uri $uri/ /index.html;
    }

    # ---------- 后端 API 反向代理 ----------
    location /api/ {
        proxy_pass http://backend_cluster;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 步骤 3：构建前端产物

```powershell
cd frontend
npm run build        # 产物输出到 frontend/dist，即 Nginx 挂载的目录
```

### 步骤 4：验证

```powershell
# 静态资源
curl.exe -s -o /dev/null -w "%{http_code} %{time_total}s\n" http://localhost/
# 实测：200  0.010s

# API 经网关转发
curl.exe -s -o /dev/null -w "%{http_code} %{time_total}s\n" http://localhost/api/devices
# 实测：401（未带令牌，鉴权生效）  0.004s
```

---

## 四、配置逐项说明

### 4.1 `upstream` —— 后端集群定义

```nginx
upstream backend_cluster {
    server platform-service:8080;
}
```

**关键点**：`platform-service` 是**容器服务名**，由 Docker 内建 DNS 解析为容器 IP。这正是"同一份代码、两套地址"策略在网关层的体现——本地开发时 Nginx 用 `host.docker.internal` 指向宿主机，容器化后改为服务名。

> **扩展方式**：追加 `server` 条目即可启用多实例，Nginx 默认按轮询策略分发请求。

### 4.2 `location /` —— 静态资源与 SPA 回退

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

**`try_files` 的语义**：按顺序尝试——

1. `$uri` —— 是否存在对应的实际文件（如 `/assets/index-xxx.js`）
2. `$uri/` —— 是否存在对应的目录
3. `/index.html` —— **都找不到则返回首页**

**为什么必须配置**：单页应用（SPA）的路由由前端 JavaScript 处理。用户在浏览器中访问 `/tasks` 或刷新该页面时，服务器上**并不存在** `/tasks` 这个实际文件。若不配置回退，Nginx 会返回 404，页面无法打开。

> 这是前后端分离项目部署时最常见的坑之一。

### 4.3 `location /api/` —— 反向代理

```nginx
location /api/ {
    proxy_pass http://backend_cluster;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

**三个 `proxy_set_header` 的作用**：

| 头部 | 作用 |
| :--- | :--- |
| `Host` | 传递原始请求的 Host，后端据此生成正确的链接 |
| `X-Real-IP` | 传递客户端真实 IP（否则后端只能看到 Nginx 的 IP） |
| `X-Forwarded-For` | 传递完整的代理链路 IP（可含多级代理） |
| `X-Forwarded-Proto` | 传递原始协议（http / https），用于判断是否 HTTPS |

> 后续若要实现基于客户端 IP 的限流或审计，这些头部是前提。

---

## 五、设计决策

### 决策 1：为什么用 Nginx 作为统一入口

**若不用反向代理，后端直接对外暴露**会遇到这些问题：

| 问题 | 反向代理的解决方式 |
| :--- | :--- |
| 后端拓扑暴露（端口、IP、实例数） | 客户端只看到 80 端口，后端拓扑被隐藏 |
| 静态资源由应用服务器处理，效率低 | Nginx 处理静态文件的效率远高于应用服务器 |
| 限流、安全策略需要在每个服务里各写一遍 | 集中在网关一处配置，全局生效 |
| 后端扩容需要通知客户端改地址 | 客户端地址不变，扩容对客户端透明 |
| 跨域问题 | 前后端同源（都由 80 端口提供），无跨域 |

**最后一条特别实际**：本项目前端与后端都经 Nginx 的 80 端口访问，属于**同源请求**，因此**完全不需要处理 CORS**。

### 决策 2：为什么前端产物由 Nginx 托管而非后端

前端构建产物是纯静态文件（HTML / CSS / JS）。Nginx 处理静态文件是基于 `sendfile` 系统调用的零拷贝，效率远高于经过应用框架的请求处理链路。

**本项目实测**：静态资源响应平均 **10.3 ms**，API 转发平均 **4.0 ms**。

### 决策 3：为什么用 `upstream` 而非直接写后端地址

```nginx
# ✗ 直接写地址：扩展多个实例时要改多处
proxy_pass http://platform-service:8080;

# ✓ 用 upstream：扩展时只需在 upstream 块中追加一行
upstream backend_cluster { server platform-service:8080; }
proxy_pass http://backend_cluster;
```

`upstream` 把"后端有哪些实例"这一信息**收敛到一个地方**，是负载均衡的配置基础。

### 决策 4：为什么配置用挂载而非打进镜像

```yaml
volumes:
  - ./nginx/conf.d:/etc/nginx/conf.d:ro
```

| 方式 | 修改配置后 | 适用性 |
| :--- | :--- | :--- |
| 打进自定义镜像 | 需重新构建镜像 + 重建容器 | 适合生产环境（配置不可变） |
| **挂载配置文件** | 改文件 + `nginx -s reload` 即可 | **适合开发调试**（本项目选择） |

课程项目需要频繁调整配置与演示，挂载方式更为便捷。

---

## 六、原理层面

### 6.1 反向代理 vs 正向代理

| | 正向代理 | **反向代理** |
| :--- | :--- | :--- |
| 代理对象 | **客户端** | **服务端** |
| 客户端是否知情 | 需要显式配置代理 | **不知情**，以为直接访问了服务器 |
| 典型场景 | 科学上网、内网穿透 | **负载均衡、统一入口、隐藏后端** |

本项目属于反向代理：客户端只与 Nginx 通信，不知道后端有多少实例、运行在哪里。

### 6.2 为什么 Nginx 能扛高并发

| 机制 | 说明 |
| :--- | :--- |
| **事件驱动 + 异步非阻塞** | 单个 worker 进程用 epoll 管理大量连接，不为每个连接创建线程 |
| **多进程模型** | master 管理配置与 worker，worker 数通常设为 CPU 核数 |
| **零拷贝（sendfile）** | 静态文件从页缓存直接送网卡，跳过用户态拷贝 |
| **内存占用低** | 本项目 8 个容器中 Nginx 内存占用最低（27 MiB） |

### 6.3 负载均衡策略

Nginx 支持多种分发策略：

| 策略 | 配置 | 说明 |
| :--- | :--- | :--- |
| **轮询（默认）** | `server a; server b;` | 依次分发 |
| 加权轮询 | `server a weight=3;` | 按权重分配 |
| IP 哈希 | `ip_hash;` | 同一客户端固定到同一后端（可保持会话） |
| 最少连接 | `least_conn;` | 分发给当前连接数最少的后端 |
| 响应时间 | `fair;`（第三方模块） | 按响应时间分配 |

本项目当前为单实例，使用默认轮询策略。

---

## 七、当前不足与优化方向

| # | 不足 | 影响 | 优化方向 |
| :--- | :--- | :--- | :--- |
| 1 | **后端为单实例** | `upstream` 负载均衡已配置但未实际发挥 | 启动第 2 个后端实例并验证轮询效果 |
| 2 | **未配置限流** | 缺少对突发流量的防护 | 配置 `limit_req_zone` + `limit_req` |
| 3 | **未配置安全响应头** | 缺少 `X-Content-Type-Options`、`X-Frame-Options` 等 | 添加 `add_header` 指令 |
| 4 | **未启用 HTTPS** | 传输为明文 | 配置 TLS 证书并监听 443 |
| 5 | **未配置压缩** | 文本资源未压缩传输 | 启用 `gzip` |
| 6 | **未配置健康检查** | 后端故障时仍会转发请求 | 配置 `max_fails` / `fail_timeout` 或主动健康检查 |
| 7 | **配置修改需手动 reload** | 易遗忘导致配置未生效 | 自动化部署流程中固化 reload 步骤 |

### 配置示例：限流与安全头

```nginx
# 在 http 块（此处为 server 外的顶层）定义限流区
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=20r/s;

server {
    listen       80;
    server_name  localhost;

    # 基础安全响应头
    add_header X-Content-Type-Options nosniff;
    add_header X-Frame-Options SAMEORIGIN;
    add_header X-XSS-Protection "1; mode=block";

    location /api/ {
        limit_req zone=api_limit burst=40 nodelay;    # 每 IP 每秒 20 次，突发允许 40
        proxy_pass http://backend_cluster;
        # ……其余头部同前
    }
}
```

---

## 附：一页速记

```text
【作用】唯一对外入口：静态托管 + API 反向代理 + 负载均衡
【分流】/       → 前端产物（try_files 回退 index.html）
        /api/   → upstream backend_cluster → platform-service:8080
【关键配置】
    · upstream 块定义后端集群（容器服务名由 Docker DNS 解析）
    · try_files $uri $uri/ /index.html   ← SPA 回退，不配则刷新 404
    · proxy_set_header 传递 Host / X-Real-IP / X-Forwarded-For
【好处】前后端同源 → 无需处理 CORS；隐藏后端拓扑；集中限流与安全
【生效】配置是挂载的 → 改完 nginx -s reload，不必重启容器
【实测】静态 10.3 ms / API 转发 4.0 ms / 内存 27 MiB
【不足】后端单实例（负载均衡未发挥）、无限流、无安全头、无 HTTPS、无 gzip
```
