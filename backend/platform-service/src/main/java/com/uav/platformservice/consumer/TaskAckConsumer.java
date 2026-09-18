package com.uav.platformservice.consumer;

import com.uav.platformservice.config.Topics;
import com.uav.platformservice.model.TaskAckMessage;
import com.uav.platformservice.service.TaskLogService;
import com.uav.platformservice.service.TaskService;
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
    private final TaskService taskService;

    public TaskAckConsumer(JsonMapper jsonMapper, TaskLogService taskLogService, TaskService taskService) {
        this.jsonMapper = jsonMapper;
        this.taskLogService = taskLogService;
        this.taskService = taskService;
    }

    /**
     * 收到设备回执 —— 喂给两个地方：
     *   ① task_log（时序集合）：追加流水，记录执行过程的每一步，7 天 TTL
     *   ② task（普通集合）：更新任务台账的当前状态与进度
     *
     * 这就是"任务台账 / 回执流水"分离设计里两条数据的分流点。
     */
    @KafkaListener(topics = Topics.DEVICE_TASK_ACK)
    public void onTaskAck(String message) {
        try {
            TaskAckMessage msg = jsonMapper.readValue(message, TaskAckMessage.class);
            taskLogService.add(msg);          // ① 追加回执流水
            taskService.applyAck(msg);        // ② 推进任务状态机
        } catch (Exception e) {
            log.error("[任务回执消费] 处理失败: {}", e.getMessage());
        }
    }
}