package com.uav.platformservice.consumer;

import com.uav.platformservice.config.Topics;
import com.uav.platformservice.model.SensorMessage;
import com.uav.platformservice.service.SensorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SensorConsumer {

    private static final Logger log = LoggerFactory.getLogger(SensorConsumer.class);

    private final JsonMapper jsonMapper;
    private final SensorService sensorService;

    public SensorConsumer(JsonMapper jsonMapper, SensorService sensorService) {
        this.jsonMapper = jsonMapper;
        this.sensorService = sensorService;
    }

    @KafkaListener(topics = Topics.DEVICE_SENSOR)
    public void onSensor(String message) {
        try {
            SensorMessage msg = jsonMapper.readValue(message, SensorMessage.class);
            sensorService.add(msg);
        } catch (Exception e) {
            log.error("[传感器消费] 处理失败: {}", e.getMessage());
        }
    }
}