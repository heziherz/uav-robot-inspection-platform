package com.uav.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扮演“无人机仿真设备”：
 * 每隔 1 秒向 Kafka topic_device_heartbeat 发一条心跳 JSON。
 */
@Component
public class DeviceHeartbeatProducer {

    private static final Logger log = LoggerFactory.getLogger(DeviceHeartbeatProducer.class);
    private static final String TOPIC = "topic_device_heartbeat";   // 与需求文档 Topic 设计一致

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private int battery = 100;   // 模拟电量递减，之后会切真实仿真逻辑

    @Scheduled(fixedRate = 1000) // 每秒发一条
    public void sendHeartbeat() {
        // 心跳消息体（先用字符串，后续第 3 步接入 MongoDB 时再结构化）
        String json = "{"
                + "\"deviceNo\":\"UAV-001\","
                + "\"deviceType\":\"DRONE\","
                + "\"battery\":" + (battery > 10 ? battery-- : 10) + ","
                + "\"status\":\"online\","
                + "\"ts\":" + System.currentTimeMillis()
                + "}";

        // 用设备编号做 key：保证同一设备的消息有序（需求文档 9.1 分区建议）
        kafkaTemplate.send(TOPIC, "UAV-001", json);
        log.info("[生产者-设备仿真] 已发送心跳: {}", json);
    }
}
