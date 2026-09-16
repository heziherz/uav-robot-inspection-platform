package com.uav.platformservice.service;

import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.repository.AlarmRepository;
import com.uav.platformservice.repository.DeviceStatusRepository;
import com.uav.platformservice.repository.MediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计分析服务（UC-17）。
 *
 * 【角色】Service 层
 * 【联系】MongoDB（设备 / 影像计数）+ **Elasticsearch（聚合统计）**
 * 【数据流】Controller → 简单计数查 MongoDB、分组统计查 ES 聚合 → 返回图表数据
 *
 * 为什么聚合走 ES：
 *   "按告警类型分组计数""按小时统计数量"这类**维度聚合**，ES 的 terms / date_histogram
 *   天然支持且基于列式存储，比 MongoDB 聚合管道更直观、效率更高 —— 这正是检索层的价值所在。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    @Value("${es.base-url}")
    private String esBase;

    private final DeviceStatusRepository deviceStatusRepository;
    private final AlarmRepository alarmRepository;
    private final MediaRepository mediaRepository;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public StatsService(DeviceStatusRepository deviceStatusRepository,
                        AlarmRepository alarmRepository,
                        MediaRepository mediaRepository,
                        JsonMapper jsonMapper) {
        this.deviceStatusRepository = deviceStatusRepository;
        this.alarmRepository = alarmRepository;
        this.mediaRepository = mediaRepository;
        this.jsonMapper = jsonMapper;
    }

    /** 总览 KPI（走 MongoDB 计数） */
    public Map<String, Object> overview() {
        List<DeviceStatus> devices = deviceStatusRepository.findAll();
        long online = devices.stream().filter(d -> "ONLINE".equals(d.getStatus())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceTotal", devices.size());
        result.put("deviceOnline", online);
        result.put("deviceOffline", devices.size() - online);
        result.put("alarmTotal", alarmRepository.count());
        result.put("alarmPending", alarmRepository
                .findByHandleStatus("PENDING", PageRequest.of(0, 1)).getTotalElements());
        result.put("mediaTotal", mediaRepository.count());
        result.put("mediaUploaded", mediaRepository.findByUploaded(true).size());
        return result;
    }

    /** 告警类型分布（ES terms 聚合） */
    public List<Map<String, Object>> alarmTypeDistribution() {
        String body = "{\"size\":0,\"aggs\":{\"g\":{\"terms\":{\"field\":\"alarmType\",\"size\":10}}}}";
        return esBuckets(body, "g");
    }

    /** 告警级别分布（ES terms 聚合） */
    public List<Map<String, Object>> alarmLevelDistribution() {
        String body = "{\"size\":0,\"aggs\":{\"g\":{\"terms\":{\"field\":\"level\",\"size\":10}}}}";
        return esBuckets(body, "g");
    }

    /** 告警趋势：按小时聚合（ES date_histogram） */
    public List<Map<String, Object>> alarmTrend() {
        String body = "{\"size\":0,\"aggs\":{\"g\":{\"date_histogram\":"
                + "{\"field\":\"eventTime\",\"fixed_interval\":\"1h\",\"min_doc_count\":0}}}}";
        return esBuckets(body, "g");
    }

    // ---------- 内部：调用 ES 聚合并解析 buckets ----------

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
}
