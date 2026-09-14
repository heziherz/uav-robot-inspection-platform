package com.uav.platformservice.consumer;

import com.uav.platformservice.model.TaskAckMessage;
import com.uav.platformservice.service.TaskLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TaskAckConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskAckConsumer.class);

    private final JsonMapper jsonMapper;
    private final TaskLogService taskLogService;

    public TaskAckConsumer(JsonMapper jsonMapper, TaskLogService taskLogService) {
        this.jsonMapper = jsonMapper;
        this.taskLogService = taskLogService;
    }

    @KafkaListener(topics = "topic_device_task_ack")
    public void onTaskAck(String message) {
        try {
            TaskAckMessage msg = jsonMapper.readValue(message, TaskAckMessage.class);
            taskLogService.add(msg);
        } catch (Exception e) {
            log.error("[任务回执消费] 处理失败: {}", e.getMessage());
        }
    }
}