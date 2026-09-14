package com.uav.platformservice.service;

import com.uav.platformservice.model.SensorDoc;
import com.uav.platformservice.model.SensorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 传感器数据写入服务（与 TelemetryService 同构）：
 *   ① 攒批：够 20 条写一次
 *   ② 兜底：每 2 秒强制 flush，避免低峰期一直不写
 */
@Service
public class SensorService {

    private static final Logger log = LoggerFactory.getLogger(SensorService.class);
    private static final int BATCH_SIZE = 20;

    private final MongoTemplate mongoTemplate;
    private final List<SensorDoc> buffer = new ArrayList<>(BATCH_SIZE);

    public SensorService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public synchronized void add(SensorMessage msg) {
        SensorDoc doc = new SensorDoc();
        doc.setDeviceNo(msg.deviceNo());
        doc.setTemperature(msg.temperature());
        doc.setHumidity(msg.humidity());
        doc.setGasValue(msg.gasValue());
        doc.setDeviceTemp(msg.deviceTemp());
        doc.setEventTime(new Date(msg.eventTime()));   // long → Date（时序集合要求）
        doc.setIngestTime(System.currentTimeMillis());

        buffer.add(doc);
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
        mongoTemplate.insert(buffer, SensorDoc.class);
        buffer.clear();
        log.info("[批量写入] 传感器数据 {} 条已入库", size);
    }
}