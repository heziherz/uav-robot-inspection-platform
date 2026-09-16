package com.uav.platformservice.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 消息发送统一入口。
 * 集中处理：对象 → JSON 序列化、发送、日志、异常。
 * 业务代码只需说明"发到哪个 topic、用什么 key、发什么对象"。
 */
@Component
public class MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    public MessagePublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 发送一条消息。
     *
     * @param topic   目标 topic（用 Topics 常量）
     * @param key     分区键（通常用 deviceNo，保证同一设备消息有序）
     * @param payload 消息体（record 对象，自动序列化为 JSON）
     */
    public void publish(String topic, String key, Object payload) {
        try {
            String json = jsonMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, key, json);
            log.info("[MQ] → {} | key={}", topic, key);
        } catch (Exception e) {
            log.error("[MQ] 发送失败 {}: {}", topic, e.getMessage());
            throw new IllegalStateException("消息发送失败: " + topic, e);
        }
    }
}
