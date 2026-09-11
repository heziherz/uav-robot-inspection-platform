package com.uav.devicesimulator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.devicesimulator.device.DeviceManager;
import com.uav.devicesimulator.device.DeviceSimulator;
import com.uav.devicesimulator.model.CommandMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 指令消费者：订阅下行 topic，把任务指令派发给对应设备。
 *
 * 注意：它是 Spring Bean（@Component），Kafka 的 @KafkaListener 才会生效。
 */
@Component
public class CommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommandConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeviceManager deviceManager;

    public CommandConsumer(DeviceManager deviceManager) {
        this.deviceManager = deviceManager;
    }

    @KafkaListener(topics = DeviceSimulator.TOPIC_COMMAND)
    public void onCommand(String message) {
        log.info("[指令消费者] 收到下行指令: {}", message);
        try {
            CommandMessage cmd = MAPPER.readValue(message, CommandMessage.class);
            DeviceSimulator device = deviceManager.find(cmd.deviceNo());
            if (device == null) {
                log.warn("[指令消费者] 未找到设备 {}，指令忽略", cmd.deviceNo());
                return;
            }
            device.handleCommand(cmd);   // 交给设备去“执行并回执”
        } catch (Exception e) {
            log.error("[指令消费者] 指令处理失败: {}", e.getMessage());
        }
    }
}