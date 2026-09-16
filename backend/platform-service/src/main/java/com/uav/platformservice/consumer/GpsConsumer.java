package com.uav.platformservice.consumer;

import com.uav.platformservice.config.Topics;
import com.uav.platformservice.model.GpsMessage;
import com.uav.platformservice.service.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 位置消费者：收到 GPS 消息 → 交给 TelemetryService 批量落库。 */
@Component
public class GpsConsumer {

    private static final Logger log = LoggerFactory.getLogger(GpsConsumer.class);

    private final JsonMapper jsonMapper;
    private final TelemetryService telemetryService;

    public GpsConsumer(JsonMapper jsonMapper, TelemetryService telemetryService) {
        this.jsonMapper = jsonMapper;
        this.telemetryService = telemetryService;
    }

    @KafkaListener(topics = Topics.DEVICE_GPS)
    public void onGps(String message) {
        try {
            GpsMessage gps = jsonMapper.readValue(message, GpsMessage.class);
            telemetryService.add(gps);
        } catch (Exception e) {
            log.error("[轨迹消费] 处理失败: {}", e.getMessage());
        }
    }
}