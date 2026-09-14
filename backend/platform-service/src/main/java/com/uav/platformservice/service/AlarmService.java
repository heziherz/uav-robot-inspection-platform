package com.uav.platformservice.service;

import com.uav.platformservice.model.AlarmDoc;
import com.uav.platformservice.model.AlarmMessage;
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
import java.util.Map;

/**
 * 告警服务：一条告警写两个地方
 *   ① MongoDB        —— 权威明细（可更新处置状态）
 *   ② Elasticsearch  —— 检索副本（按类型/时间/地点检索）
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
    private static final String ES_BASE = "http://localhost:9200";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final AlarmRepository alarmRepository;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AlarmService(AlarmRepository alarmRepository, JsonMapper jsonMapper) {
        this.alarmRepository = alarmRepository;
        this.jsonMapper = jsonMapper;
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
                    .uri(URI.create(ES_BASE + "/" + indexName() + "/_doc/" + doc.getAlarmId()))
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
}