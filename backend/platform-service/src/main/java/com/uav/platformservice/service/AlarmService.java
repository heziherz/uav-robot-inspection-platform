package com.uav.platformservice.service;

import com.uav.platformservice.common.MessagePublisher;
import com.uav.platformservice.config.Topics;
import com.uav.platformservice.model.AlarmDoc;
import com.uav.platformservice.model.AlarmMessage;
import com.uav.platformservice.model.CommandMessage;
import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.repository.AlarmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

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
    private final MessagePublisher messagePublisher;
    private final com.uav.platformservice.repository.DeviceStatusRepository deviceStatusRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AlarmService(AlarmRepository alarmRepository,
                        JsonMapper jsonMapper,
                        MessagePublisher messagePublisher,
                        com.uav.platformservice.repository.DeviceStatusRepository deviceStatusRepository) {
        this.alarmRepository = alarmRepository;
        this.jsonMapper = jsonMapper;
        this.messagePublisher = messagePublisher;
        this.deviceStatusRepository = deviceStatusRepository;
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

    private void dispatchReviewTask(AlarmDoc alarm) {
        // 选一台在线机器狗（简化策略：第一台 ONLINE 的 ROBOT_DOG）
        String target = deviceStatusRepository.findAll().stream()
                .filter(d -> "ROBOT_DOG".equals(d.getDeviceType()) && "ONLINE".equals(d.getStatus()))
                .map(DeviceStatus::getDeviceNo)
                .findFirst()
                .orElse(null);

        if (target == null) {
            log.warn("[告警处置] 无在线机器狗，复核指令未下发");
            return;
        }

        long now = System.currentTimeMillis();
        String taskId = "TASK-" + now;

        // 用 record 对象描述指令（不再手拼 Map）
        CommandMessage command = new CommandMessage(
                "CMD-" + taskId,
                taskId,
                target,
                "REVIEW",
                alarm.getAlarmId(),
                "",
                now
        );

        messagePublisher.publish(Topics.PLATFORM_COMMAND, target, command);
        log.info("[告警处置] 已向 {} 下发复核指令: taskId={}", target, taskId);
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
     * 分页查询告警（服务端分页）。
     * 返回 { total, items } —— 前端据此做真正的分页，不必一次性加载上千条数据。
     */
    public Map<String, Object> pageAlarms(String status, int page, int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(page - 1, 0),
                size,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "eventTime"));

        org.springframework.data.domain.Page<AlarmDoc> result =
                (status == null || status.isBlank() || "ALL".equals(status))
                        ? alarmRepository.findAll(pageable)
                        : alarmRepository.findByHandleStatus(status, pageable);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("items", result.getContent());
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