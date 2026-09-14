package com.uav.platformservice.consumer;

import com.uav.platformservice.model.AlarmMessage;
import com.uav.platformservice.service.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AlarmConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmConsumer.class);

    private final JsonMapper jsonMapper;
    private final AlarmService alarmService;

    public AlarmConsumer(JsonMapper jsonMapper, AlarmService alarmService) {
        this.jsonMapper = jsonMapper;
        this.alarmService = alarmService;
    }

    @KafkaListener(topics = "topic_device_alarm")
    public void onAlarm(String message) {
        try {
            AlarmMessage alarm = jsonMapper.readValue(message, AlarmMessage.class);
            alarmService.handleAlarm(alarm);
        } catch (Exception e) {
            log.error("[告警消费] 处理失败: {}", e.getMessage());
        }
    }
}