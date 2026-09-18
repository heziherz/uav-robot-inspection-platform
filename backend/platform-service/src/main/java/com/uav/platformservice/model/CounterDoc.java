package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 通用原子计数器（MongoDB 集合 counter）。
 *
 * 【为什么需要它 —— 修的是一个真实 Bug】
 *   改造前 taskId 是 `"TASK-" + System.currentTimeMillis()`，只有毫秒精度。
 *   而告警批量处置是「循环调用单条」，一轮循环往往不到 1 毫秒
 *   → 多个任务拿到同一个 taskId
 *   → msgId 由 taskId 派生，也重复
 *   → 设备侧幂等保护把重复 msgId 的指令当"重复投递"静默丢弃
 *   → 结果：勾选 3 条告警指派复核，设备只执行 1 条，前端却提示"已指派 3 条"。
 *
 *   教训：幂等设计本身是对的，但它假设了 ID 唯一。
 *         ID 生成出错时，幂等会退化成"静默丢数据"。
 *
 * 【为什么不用内存 AtomicInteger】
 *   JVM 重启后从 1 重新开始，当天已存在的任务会碰撞。
 *   MongoDB 的 findAndModify 是原子操作，跨进程、跨重启都唯一。
 *
 * 文档形如：{ _id: "task-20260917", seq: 42 }
 */
@Document("counter")
public class CounterDoc {

    @Id
    private String id;      // 计数器名称，如 "task-20260917"
    private Long seq;       // 当前值

    public CounterDoc() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
}
