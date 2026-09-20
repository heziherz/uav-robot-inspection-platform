# Elasticsearch 实现说明

> **本文结构**：作用 → 实现方式 → 步骤与代码 → 设计决策 → 原理 → 不足
> **配套**：其他组件见同目录《HDFS 实现说明》《Kafka 实现说明》《MongoDB 实现说明》《Nginx 实现说明》

---

## 一、在项目中做什么

**核心前提：Elasticsearch 不是主存储，它只承担两件事。**

| 用途 | 实现位置 | 说明 |
| :--- | :--- | :--- |
| **① 检索副本**（双写） | `AlarmService.indexToEs()` | 告警写入 MongoDB 的同时写一份到 ES |
| **② 聚合统计** | `StatsService` 的三个方法 | 统计分析页的 3 个图表**全部**走 ES 聚合 |
| **③ 运维旁路** | Kibana | 直连 ES，不经过业务网关 |

**一个容易误解的点**：告警**列表查询（多条件筛选）走的是 MongoDB，不是 ES**。ES 只负责**聚合统计**。

**表 1-1 MongoDB 与 ES 的分工**

| 需求 | 由谁承担 | 理由 |
| :--- | :--- | :--- |
| 告警列表多条件筛选 + 分页 | **MongoDB** | 需读取完整明细、需支持处置状态更新 |
| 处置状态更新（PENDING→HANDLED） | **MongoDB** | ES 擅长追加，不擅长频繁更新单文档 |
| 按类型 / 级别分组统计 | **Elasticsearch** | `terms` 聚合是原生能力 |
| 按时间分桶统计趋势 | **Elasticsearch** | `date_histogram` 原生支持 |

---

## 二、实现方式

### 2.1 技术选择：裸 HTTP，零额外依赖

项目**没有**引入 `elasticsearch-java` 客户端，也没有使用 `spring-boot-starter-data-elasticsearch`，而是用 JDK 自带的 `java.net.http.HttpClient` 直接发 REST 请求：

```java
private final HttpClient httpClient = HttpClient.newHttpClient();

HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(esBase + "/" + indexName() + "/_doc/" + doc.getAlarmId()))
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(json))
        .build();
httpClient.send(request, HttpResponse.BodyHandlers.ofString());
```

**选型理由**：

| 理由 | 说明 |
| :--- | :--- |
| **需求量极小** | 只用两个操作——索引文档、搜索。HTTP 完全够用 |
| **避开版本强耦合** | ES 客户端与服务端**版本必须严格匹配**，是出了名的工程坑。裸 HTTP 只依赖 REST 协议，服务端升级不影响代码 |
| **依赖树轻** | 官方 Java Client 会带入一整棵依赖树，与项目已有的 Jackson 3、Spring Boot 4 容易冲突 |
| **语义透明** | 每个请求长什么样一目了然，便于排查与讲解 |

**代价**：手写 JSON body（无类型安全）、手动解析响应、**未配置超时与连接池**。

### 2.2 只用到的三类 REST 操作

| 操作 | HTTP 方法 | 路径 | 用途 |
| :--- | :--- | :--- | :--- |
| 索引文档 | `PUT` | `/{index}/_doc/{id}` | 写入告警（指定 `_id` 实现幂等） |
| 搜索 | `POST` | `/alarm-*/_search` | 多条件过滤（`bool.filter`） |
| 聚合 | `POST` | `/alarm-*/_search` | 分组与分桶（`terms` / `date_histogram`） |

---

## 三、步骤与代码

### 步骤 1：部署容器并配置地址

```yaml
# docker-compose.yml
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:9.5.3
  container_name: elasticsearch
  environment:
    - discovery.type=single-node        # 单节点模式（免去集群发现配置）
    - xpack.security.enabled=false      # 关闭鉴权，便于演示
    - ES_JAVA_OPTS=-Xms512m -Xmx512m    # 堆内存（必须显式设置）
  mem_limit: 1g
  ports:
    - "9200:9200"
  volumes:
    - es-data:/usr/share/elasticsearch/data

kibana:
  image: docker.elastic.co/kibana/kibana:9.5.3
  environment:
    - ELASTICSEARCH_HOSTS=http://elasticsearch:9200
    - I18N_LOCALE=zh-CN
```

```properties
# application.properties（本地开发）
es.base-url=http://localhost:9200
```

```yaml
# 容器内通过环境变量覆盖为服务名
ES_BASE_URL: http://elasticsearch:9200
```

> **注意 `ES_JAVA_OPTS`**：不显式设置堆内存时，ES 会按容器可用内存自动推断。在 8 GB 配额的环境下容易与 JVM 默认策略冲突，**必须设置**。

### 步骤 2：写入文档（索引）

```java
/** 索引名按天滚动：alarm-2026.09.18 */
private String indexName() {
    return "alarm-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
}

private void indexToEs(AlarmDoc doc) {
    try {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("alarmId", doc.getAlarmId());
        body.put("deviceNo", doc.getDeviceNo());
        body.put("alarmType", doc.getAlarmType());
        body.put("level", doc.getLevel());
        body.put("description", doc.getDescription());
        body.put("lat", doc.getLat());
        body.put("lng", doc.getLng());
        body.put("handleStatus", doc.getHandleStatus());
        // ES 的 date 类型：必须是 ISO-8601 字符串，不能是毫秒时间戳
        body.put("eventTime", Instant.ofEpochMilli(doc.getEventTime()).toString());

        String json = jsonMapper.writeValueAsString(body);

        // PUT /{index}/_doc/{id} —— 用 alarmId 作 _id，天然幂等
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(esBase + "/" + indexName() + "/_doc/" + doc.getAlarmId()))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            log.info("[告警索引] 已写入 ES: {}", indexName());
        } else {
            log.error("[告警索引] ES 返回异常状态 {}: {}", resp.statusCode(), resp.body());
        }
    } catch (Exception e) {
        // 索引失败不影响 MongoDB 里的权威数据（ES 是可重建的副本）
        log.error("[告警索引] 写入 ES 失败: {}", e.getMessage());
    }
}
```

**两个关键点**：

**① `PUT /{index}/_doc/{id}` 而非 `POST /{index}/_doc`**

后者由 ES 自动生成 ID，每次调用都新增文档；前者用业务主键 `alarmId` 作 `_id`。由于 `_id` 在索引内唯一，**重复写入会变成覆盖更新**，从而使 ES 写入天然具备幂等性。

> **必要性**：Kafka 是"至少一次"投递语义，消息重复消费是常态。若 ES 写入不幂等，重复消费会产生重复告警文档，统计数字随之虚高。

**② `date` 字段必须是 ISO-8601 字符串**

ES 的 `date` 类型不接受 long 型毫秒时间戳，必须转为 `2026-09-18T02:03:38.229Z` 格式。这与 MongoDB 时序集合要求 `Date` 类型属于同一类问题——**跨系统传递时间，必须核对格式**。

### 步骤 3：搜索（`bool.filter`）

```java
public List<Map<String, Object>> searchInEs(String alarmType, String level) {
    // bool/filter：不计算相关性评分、可被 ES 缓存 —— 过滤型检索的推荐写法
    Map<String, Object> bool = new LinkedHashMap<>();
    List<Map<String, Object>> filters = new ArrayList<>();

    if (alarmType != null && !alarmType.isBlank())
        filters.add(Map.of("term", Map.of("alarmType", alarmType)));
    if (level != null && !level.isBlank())
        filters.add(Map.of("term", Map.of("level", level)));

    bool.put("filter", filters);
    Map<String, Object> body = Map.of("query", Map.of("bool", bool));

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(esBase + "/alarm-*/_search?size=50&sort=eventTime:desc"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
            .build();
    // ……解析响应
}
```

**`alarm-*` 通配的含义**：由于索引按天滚动，查询"所有告警"需要跨全部按天索引，用通配符匹配。ES 会并行查询所有匹配索引再合并结果。

### 步骤 4：聚合统计

```java
/** 告警类型分布（terms：按字段值分组计数） */
public List<Map<String, Object>> alarmTypeDistribution() {
    String body = "{\"size\":0,\"aggs\":{\"g\":{\"terms\":{\"field\":\"alarmType\",\"size\":10}}}}";
    return esBuckets(body, "g");
}

/** 告警级别分布（terms） */
public List<Map<String, Object>> alarmLevelDistribution() {
    String body = "{\"size\":0,\"aggs\":{\"g\":{\"terms\":{\"field\":\"level\",\"size\":10}}}}";
    return esBuckets(body, "g");
}

/** 告警趋势（date_histogram：按时间分桶） */
public List<Map<String, Object>> alarmTrend() {
    String body = "{\"size\":0,\"aggs\":{\"g\":{\"date_histogram\":"
            + "{\"field\":\"eventTime\",\"fixed_interval\":\"1h\",\"min_doc_count\":0}}}}";
    return esBuckets(body, "g");
}
```

**`"size":0` 的含义**：不返回命中文档本身，只要聚合结果。统计图表不需要原始记录，返回 0 条可大幅减少网络传输。

**表 3-1 两类聚合的对比**

| 聚合 | 作用 | 本项目用途 |
| :--- | :--- | :--- |
| `terms` | 按**字段值**分组计数 | 告警类型分布、级别分布（饼图/柱状图） |
| `date_histogram` | 按**时间间隔**分桶计数 | 告警趋势（折线图），`1h` = 按小时 |

### 步骤 5：解析响应

```java
@SuppressWarnings("unchecked")
private List<Map<String, Object>> esBuckets(String queryBody, String aggName) {
    List<Map<String, Object>> result = new ArrayList<>();
    try {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(esBase + "/alarm-*/_search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(queryBody))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.error("[统计] ES 返回异常 {}: {}", resp.statusCode(), resp.body());
            return result;
        }

        Map<String, Object> root = jsonMapper.readValue(resp.body(), Map.class);
        Map<String, Object> aggs = (Map<String, Object>) root.get("aggregations");
        Map<String, Object> agg = (Map<String, Object>) aggs.get(aggName);
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) agg.get("buckets");

        for (Map<String, Object> b : buckets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", b.get("key_as_string") != null ? b.get("key_as_string") : b.get("key"));
            item.put("count", b.get("doc_count"));
            result.add(item);
        }
    } catch (Exception e) {
        log.error("[统计] ES 聚合失败: {}", e.getMessage());
    }
    return result;
}
```

**响应结构逐层剥离**：

```json
{
  "took": 5,
  "hits": { "total": { "value": 2183 } },
  "aggregations": {
    "g": {
      "buckets": [
        { "key": "ENV",      "doc_count": 1204 },
        { "key": "OVERHEAT", "doc_count": 651  }
      ]
    }
  }
}
```

**一处细节**：`date_histogram` 返回的 `key` 是毫秒时间戳、`key_as_string` 是格式化字符串；而 `terms` 聚合**没有** `key_as_string`。因此代码做了兼容取值，避免两种聚合需要写两套解析逻辑。

---

## 四、设计决策

### 决策 1：双写，但明确权威数据源

```text
告警产生
   ├─→ MongoDB（权威明细）      ← 业务查询、状态更新以它为准
   └─→ Elasticsearch（检索副本）← 聚合统计走它，可随时重建
```

**为什么采用双写而非只写一个**：

- 若只写 MongoDB：分组统计需写聚合管道，数据量大时受内存限制；
- 若只写 ES：处置状态更新是"频繁更新单文档"，这正是 ES 的弱项。

**关键认知：ES 是可重建的副本。** 任何时刻都可以从 MongoDB 全量重建 ES 索引。因此代码中 ES 写入失败**只记日志、不抛异常**：

```java
} catch (Exception e) {
    log.error("[告警索引] 写入 ES 失败: {}", e.getMessage());   // 不影响主流程
}
```

这就是"**先明确权威数据源**"的价值——冲突时的处理原则是清晰且不需要临时决策的。

### 决策 2：索引按天滚动

```text
alarm-2026.09.16    508 条
alarm-2026.09.17   1596 条
alarm-2026.09.18     79 条
```

三个收益：

1. **单索引体积可控** —— 不会随时间无限膨胀导致查询性能衰减；
2. **便于清理归档** —— 删除 `alarm-2026.09.01` 即可清理该日数据；
3. **便于按时间范围查询** —— 查最近 3 天只需命中 3 个索引。

### 决策 3：`_id` 使用业务主键保证幂等

用业务主键作 `_id`，让重复写入变成覆盖更新，是最简单可靠的幂等方案。

### 决策 4：聚合交给 ES，业务查询留给 MongoDB

> `terms` 聚合（按字段分组计数）与 `date_histogram`（按时间分桶）是 ES 的原生能力，底层依靠倒排索引与 `doc_values` 列式存储，效率极高。
>
> 若在 MongoDB 中做同样的事，需要编写聚合管道 `$group` + `$sort`，且数据量大时会触及聚合管道的内存限制（默认 100 MB，超出需显式开启 `allowDiskUse`）。
>
> **同一需求在不同存储上的实现代价可能相差一个数量级**——按数据特征选择存储，这正是"异构存储"的价值所在。

---

## 五、原理层面：ES 为什么快

| 机制 | 作用 |
| :--- | :--- |
| **倒排索引** | 建立"词项 → 文档列表"的映射，全文检索无需遍历文档 |
| **doc_values 列式存储** | 聚合时只需读取目标列，不必加载整行——**这是聚合快的根本原因** |
| **filter 不参与打分** | 跳过分词与相关度计算，且结果可被缓存 |
| **分片并行** | 查询分发到多个分片并行执行后合并结果 |

**`must` 与 `filter` 的区别**：

| | `must` | `filter` |
| :--- | :--- | :--- |
| 计算相关性得分 | ✅ | ❌ |
| 结果可否缓存 | ❌ | ✅ |
| 适用场景 | 全文检索（需按相关度排序） | **精确过滤** |

本项目的条件是"类型 = ENV"这类精确匹配，不需要相关度排序，因此使用 `filter`。

---

## 六、当前不足与优化方向

| # | 不足 | 影响 | 优化方向 |
| :--- | :--- | :--- | :--- |
| 1 | **未定义 index template** | 经纬度被动态映射为普通对象而非 `geo_point`，**地理检索无法使用** | 定义索引模板显式声明字段类型 |
| 2 | **只做了过滤检索，未做全文检索** | 使用 `term` 精确匹配，无分词与 `match` 查询 | 为描述字段配置分词器并使用 `match` |
| 3 | **写入为同步阻塞** | 消费线程需等待 ES 响应，ES 变慢会直接拖慢消费 | 改为异步写入或引入缓冲 |
| 4 | **HttpClient 无超时配置** | 默认无限等待，ES 卡住会挂死消费线程 | 配置 connectTimeout 与 requestTimeout |
| 5 | **无批量写入** | 一条告警一次 HTTP 请求，未使用 `_bulk` | 攒批后使用 `_bulk` 接口 |
| 6 | **Kibana 仪表盘未配置** | 容器已运行但未建立索引模式与图表 | 创建索引模式并配置图表 |

---

## 附：一页速记

```text
【作用】检索副本 + 聚合统计（业务查询仍走 MongoDB）
【方式】裸 HTTP（JDK HttpClient），不用官方客户端
【写入】PUT /alarm-{yyyy.MM.dd}/_doc/{alarmId}   ← _id 用业务主键 → 幂等
【查询】POST /alarm-*/_search  body: bool.filter + term
【统计】POST /alarm-*/_search  body: size=0 + aggs（terms / date_histogram）
【关键】· date 字段必须 ISO-8601 字符串
        · ES 是可重建副本 → 写入失败只记日志、不影响主流程
        · filter 不算分、可缓存，优于 must
【不足】无 index template（geo_point 失效）、无超时、无批量、未做全文检索
```
