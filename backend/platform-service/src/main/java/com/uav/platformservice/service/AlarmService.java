package com.uav.platformservice.service;

import com.uav.platformservice.model.AlarmDoc;
import com.uav.platformservice.model.AlarmMessage;
import com.uav.platformservice.repository.AlarmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警服务：一条告警写两个地方
 *   ① MongoDB        —— 权威明细（可更新处置状态）
 *   ② Elasticsearch  —— 检索副本（按类型/时间/地点检索）
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
//    private static final String ES_BASE = "http://localhost:9200";
    @org.springframework.beans.factory.annotation.Value("${es.base-url}")
    private String esBase;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final AlarmRepository alarmRepository;
    private final JsonMapper jsonMapper;
    private final TaskService taskService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final MongoTemplate mongoTemplate;

    public AlarmService(AlarmRepository alarmRepository,
                        JsonMapper jsonMapper,
                        TaskService taskService,
                        MongoTemplate mongoTemplate) {
        this.alarmRepository = alarmRepository;
        this.jsonMapper = jsonMapper;
        this.taskService = taskService;
        this.mongoTemplate = mongoTemplate;
    }

    public void handleAlarm(AlarmMessage msg) {
        // ---------- ① 写 MongoDB ----------
        AlarmDoc doc = toDoc(msg);
        alarmRepository.save(doc);
        log.info("[告警入库] {} {} {} - {}", doc.getDeviceNo(), doc.getLevel(),
                doc.getAlarmType(), doc.getDescription());

        // ---------- ② 写 Elasticsearch ----------
        indexToEs(doc);
    }

    private AlarmDoc toDoc(AlarmMessage msg) {
        AlarmDoc doc = new AlarmDoc();
        doc.setAlarmId(msg.alarmId());
        doc.setDeviceNo(msg.deviceNo());
        doc.setAlarmType(msg.alarmType());
        doc.setLevel(msg.level());
        doc.setLat(msg.lat());
        doc.setLng(msg.lng());
        doc.setMediaFileId(msg.mediaFileId());
        doc.setDescription(msg.description());
        doc.setEventTime(msg.eventTime());
        doc.setIngestTime(System.currentTimeMillis());
        doc.setHandleStatus("PENDING");     // 新告警默认“待处理”
        return doc;
    }
    /**
     * 处置告警（UC-15）—— 由 AlarmController 调用。
     * action = CLOSE ：确认并关闭（PENDING → HANDLED）
     * action = REVIEW：指派机器狗抵近复核（PENDING → REVIEWING，同时下发任务指令）
     */
    public AlarmDoc handleAlarmAction(String alarmId, String action, String handleBy, String remark) {
        AlarmDoc doc = alarmRepository.findById(alarmId)
                .orElseThrow(() -> new IllegalArgumentException("告警不存在: " + alarmId));

        long now = System.currentTimeMillis();
        doc.setHandleBy(handleBy);
        doc.setHandleTime(now);
        doc.setHandleRemark(remark);

        if ("REVIEW".equals(action)) {
            doc.setHandleStatus("REVIEWING");
            alarmRepository.save(doc);
            indexToEs(doc);                  // 同步检索副本
            dispatchReviewTask(doc);         // 下发复核指令
            log.info("[告警处置] {} 已指派复核，处置人={}", alarmId, handleBy);
        } else {
            doc.setHandleStatus("HANDLED");
            alarmRepository.save(doc);
            indexToEs(doc);
            log.info("[告警处置] {} 已确认关闭，处置人={}", alarmId, handleBy);
        }
        return doc;
    }

    /**
     * 指派复核：创建一条 REVIEW 任务并立即下发。
     *
     * 【改造说明】原来这里自己生成 taskId 并直接发 Kafka，有两个问题：
     *   ① taskId = "TASK-" + 毫秒，而批量处置是循环调用本方法，一轮循环常常不到 1 毫秒
     *      → 多个任务 taskId 相同 → msgId 派生后也相同
     *      → 被设备侧幂等保护当成"重复投递"静默丢弃
     *      → 勾选 3 条告警指派复核，设备只执行 1 条，前端却提示"已指派 3 条"
     *   ② 下发的任务没有台账，事后查不到执行历史
     *
     * 现在统一交给 TaskService：taskId 由 MongoDB 原子计数器生成（跨重启唯一），
     * 设备选择逻辑也收敛在那边，任务全程可追踪（UC-13）。
     */
    private void dispatchReviewTask(AlarmDoc alarm) {
        taskService.createReviewTask(
                alarm.getAlarmId(),
                alarm.getAlarmType(),
                "告警复核：" + alarm.getDescription(),
                "system");
    }
    /** 索引名按天滚动（契约 §7）：alarm-2026.09.14 */
    private String indexName() {
        return "alarm-" + LocalDate.now().format(DAY);
    }

    private void indexToEs(AlarmDoc doc) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("alarmId", doc.getAlarmId());
            body.put("deviceNo", doc.getDeviceNo());
            body.put("alarmType", doc.getAlarmType());
            body.put("level", doc.getLevel());
            // geo_point 需要 {lat, lon} 对象格式
            body.put("location", Map.of("lat", doc.getLat(), "lon", doc.getLng()));
            body.put("description", doc.getDescription());
            body.put("mediaFileId", doc.getMediaFileId());
            body.put("handleStatus", doc.getHandleStatus());
            // ES 的 date 类型：ISO-8601 字符串
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
    /**
     * 多条件分页查询告警（服务端分页 + 动态筛选）。
     *
     * 用 MongoTemplate + Criteria **动态拼条件**，而不是 Repository 方法名 ——
     * 因为筛选条件是"任意可选组合"（6 个条件共 2^6 种组合，方法名写法无法覆盖）。
     *
     * @param status    处置状态（PENDING / REVIEWING / HANDLED；ALL 或空 = 不限）
     * @param deviceNo  设备编号（精确匹配）
     * @param alarmType 告警类型（精确匹配）
     * @param level     告警级别（精确匹配）
     * @param startTime 发生时间起点（毫秒时间戳，含）
     * @param endTime   发生时间终点（毫秒时间戳，含）
     */
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

    /**
     * 批量处置告警：逐条复用单条处置逻辑。
     * 单条失败不影响其他条目（记录日志后继续）。
     */
    public int handleBatch(List<String> alarmIds, String action, String handleBy, String remark) {
        if (alarmIds == null || alarmIds.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (String id : alarmIds) {
            try {
                handleAlarmAction(id, action, handleBy, remark);
                success++;
            } catch (Exception e) {
                log.error("[告警批量处置] {} 失败: {}", id, e.getMessage());
            }
        }
        log.info("[告警批量处置] 成功 {}/{} 条，动作={}", success, alarmIds.size(), action);
        return success;
    }
    /**
     * 在 ES 里检索告警（支持按类型、级别过滤）。
     * 用 bool/filter 查询：不计算相关性评分、可被 ES 缓存 —— 过滤型检索的推荐写法。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchInEs(String alarmType, String level) {
        try {
            // ① 组装查询条件（有哪个加哪个）
            List<Map<String, Object>> filters = new java.util.ArrayList<>();
            if (alarmType != null && !alarmType.isBlank()) {
                filters.add(Map.of("term", Map.of("alarmType", alarmType)));
            }
            if (level != null && !level.isBlank()) {
                filters.add(Map.of("term", Map.of("level", level)));
            }

            Map<String, Object> query = filters.isEmpty()
                    ? Map.of("query", Map.of("match_all", Map.of()))
                    : Map.of("query", Map.of("bool", Map.of("filter", filters)));

            String body = jsonMapper.writeValueAsString(query);

            // ② 发检索请求（alarm-* 匹配所有按天滚动的索引）
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(esBase + "/alarm-*/_search?size=50&sort=eventTime:desc"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.error("[告警检索] ES 返回异常 {}: {}", resp.statusCode(), resp.body());
                return List.of();
            }

            // ③ 解析 hits.hits[]._source
            Map<String, Object> root = jsonMapper.readValue(resp.body(), Map.class);
            Map<String, Object> hits = (Map<String, Object>) root.get("hits");
            List<Map<String, Object>> hitList = (List<Map<String, Object>>) hits.get("hits");

            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Map<String, Object> hit : hitList) {
                result.add((Map<String, Object>) hit.get("_source"));
            }
            log.info("[告警检索] ES 命中 {} 条 (type={}, level={})", result.size(), alarmType, level);
            return result;

        } catch (Exception e) {
            log.error("[告警检索] 失败: {}", e.getMessage());
            return List.of();
        }
    }
}