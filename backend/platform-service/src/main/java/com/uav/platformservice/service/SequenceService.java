package com.uav.platformservice.service;

import com.uav.platformservice.model.CounterDoc;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 序号生成服务（基础设施层）。
 *
 * 用 MongoDB 的 findAndModify 实现原子自增，生成全局唯一的业务编号。
 *
 * 【为什么必须走数据库】
 *   · 用 System.currentTimeMillis()：毫秒内并发会碰撞（这就是原来那个 Bug）
 *   · 用内存 AtomicInteger：JVM 重启后从 1 重来，当天已有编号会碰撞
 *   · MongoDB findAndModify：原子操作，跨线程、跨进程、跨重启都唯一
 *
 * 编号形式：TASK-20260917-0001（日期 + 当日序号），可读、可排序、便于排查。
 */
@Service
public class SequenceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PREFIX = "task-";

    private final MongoTemplate mongoTemplate;

    public SequenceService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 生成下一个任务编号：TASK-{yyyyMMdd}-{4位当日序号}。
     *
     * findAndModify 的三个要点：
     *   upsert(true)    —— 当天第一次调用时自动创建计数器文档
     *   inc("seq", 1)   —— 原子自增，两个线程同时来也只会拿到不同的值
     *   returnNew(true) —— 返回自增【之后】的值（而不是之前）
     */
    public String nextTaskId() {
        String day = LocalDate.now().format(DAY);
        CounterDoc counter = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(PREFIX + day)),
                new Update().inc("seq", 1L),
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                CounterDoc.class);

        long seq = (counter == null || counter.getSeq() == null) ? 1L : counter.getSeq();
        return "TASK-" + day + "-" + String.format("%04d", seq);
    }
}
