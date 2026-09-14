package com.uav.platformservice.service;

import com.uav.platformservice.model.TaskAckMessage;
import com.uav.platformservice.model.TaskLogDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 任务日志写入服务（与 SensorService / TelemetryService 同构：攒批 + 超时兜底） */
@Service
public class TaskLogService {

    private static final Logger log = LoggerFactory.getLogger(TaskLogService.class);
    private static final int BATCH_SIZE = 20;

    private final MongoTemplate mongoTemplate;
    private final List<TaskLogDoc> buffer = new ArrayList<>(BATCH_SIZE);

    public TaskLogService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public synchronized void add(TaskAckMessage msg) {
        TaskLogDoc doc = new TaskLogDoc();
        doc.setDeviceNo(msg.deviceNo());
        doc.setTaskId(msg.taskId());
        doc.setStage(msg.stage());
        doc.setProgress(msg.progress());
        doc.setRemark(msg.remark());
        doc.setEventTime(new Date(msg.eventTime()));
        doc.setIngestTime(System.currentTimeMillis());

        buffer.add(doc);
        log.info("[任务回执] {} 任务 {} 阶段 {}", msg.deviceNo(), msg.taskId(), msg.stage());

        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    @Scheduled(fixedRate = 2000)
    public synchronized void flushByTime() {
        flush();
    }

    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        int size = buffer.size();
        mongoTemplate.insert(buffer, TaskLogDoc.class);
        buffer.clear();
        log.info("[批量写入] 任务日志 {} 条已入库", size);
    }
}