package com.uav.platformservice.service;

import com.uav.platformservice.model.GpsMessage;
import com.uav.platformservice.model.TelemetryDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 轨迹数据写入服务 —— 演示“海量数据”的正确写法：
 *   ① 批量写入：攒够 BATCH_SIZE 条一次性插入，而不是逐条 insert
 *   ② 超时兜底：每 2 秒强制 flush，避免低峰期消息一直攒着不写
 *
 * 用 synchronized 保证缓冲区在多线程消费下的安全。
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final int BATCH_SIZE = 100;      // 攒够 100 条写一次

    private final MongoTemplate mongoTemplate;
    private final List<TelemetryDoc> buffer = new ArrayList<>(BATCH_SIZE);

    public TelemetryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** 收一条轨迹：进缓冲区，够一批就写 */
    public synchronized void add(GpsMessage gps) {
        TelemetryDoc doc = new TelemetryDoc();
        doc.setDeviceNo(gps.deviceNo());
        doc.setLat(gps.lat());
        doc.setLng(gps.lng());
        doc.setAltitude(gps.altitude());
        doc.setSpeed(gps.speed());
        doc.setHeading(gps.heading());
        doc.setEventTime(new Date(gps.eventTime()));
        doc.setIngestTime(System.currentTimeMillis());

        buffer.add(doc);
        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /** 定时兜底：每 2 秒把缓冲区里剩下的写掉 */
    @Scheduled(fixedRate = 2000)
    public synchronized void flushByTime() {
        flush();
    }

    /** 批量写入 */
    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        int size = buffer.size();
        mongoTemplate.insert(buffer, TelemetryDoc.class);   // ← 一次网络往返写 N 条
        buffer.clear();
        log.info("[批量写入] 轨迹数据 {} 条已入库", size);
    }
}