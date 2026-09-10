package com.uav.heartbeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 扮演“平台接入侧消费者”（第 3 步升级）：
 * 收到心跳 → 解析 JSON → 写入 MongoDB（覆盖该设备最新状态）。
 * 这正是真实巡检系统里“设备状态入库，供前端/态势展示”的第一步。
 */
@Component
public class HeartbeatConsumer {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatConsumer.class);

    @Autowired
    private DeviceStatusRepository repository;

    @Autowired
    private ObjectMapper objectMapper;   // Spring 已提供 Jackson，可直接注入

    @KafkaListener(topics = "topic_device_heartbeat")
    public void onHeartbeat(String message) {
        log.info("[消费者] 收到心跳: {}", message);
        try {
            // 1. 心跳 JSON 反序列化为设备状态对象
            DeviceStatus status = objectMapper.readValue(message, DeviceStatus.class);
            // 2. 以 deviceNo 为主键写入 MongoDB（同编号=覆盖更新，保留最新心跳）
            DeviceStatus saved = repository.save(status);
            log.info("[消费者] 已写入 MongoDB: {}", saved);
        } catch (Exception e) {
            log.error("[消费者] 心跳处理失败: {}", e.getMessage());
        }
    }
}
