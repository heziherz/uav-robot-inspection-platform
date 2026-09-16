package com.uav.platformservice.consumer;

import com.uav.platformservice.config.Topics;
import com.uav.platformservice.model.HeartbeatMessage;
import com.uav.platformservice.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;      // ← Jackson 3 的包名

/**
 * 心跳消费者：接收设备心跳 → 交给 DeviceService 落库。
 */
@Component
public class HeartbeatConsumer {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatConsumer.class);
    private final JsonMapper jsonMapper;        // Spring Boot 4 自动配置（Jackson 3）
    private final DeviceService deviceService;

    public HeartbeatConsumer(JsonMapper jsonMapper, DeviceService deviceService) {
        this.jsonMapper = jsonMapper;
        this.deviceService = deviceService;
    }

    @KafkaListener(topics = Topics.DEVICE_HEARTBEAT)
    public void onHeartbeat(String message) {
        try {
            // Jackson 3 的异常是 unchecked，不需要 throws
            HeartbeatMessage hb = jsonMapper.readValue(message, HeartbeatMessage.class);
            deviceService.handleHeartbeat(hb);
        } catch (Exception e) {
            log.error("[心跳消费] 处理失败: {}", e.getMessage());
        }
    }
}