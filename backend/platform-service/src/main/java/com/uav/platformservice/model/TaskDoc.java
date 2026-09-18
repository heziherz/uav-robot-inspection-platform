package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 巡检任务台账（MongoDB 集合 task）。
 *
 * 【与 task_log 的分工 —— 这是本模块的核心设计】
 *   task     集合：任务的【当前状态】，一个任务一份，会被反复更新（改状态、改进度）
 *   task_log 集合：任务执行过程的【回执流水】，只追加，时序集合，7 天 TTL
 *
 * 为什么要分开：
 *   两者读写模式完全不同。任务要频繁更新单个文档（进度 0→50→100），适合普通集合；
 *   回执是追加型时间序列，适合时序集合且能自动过期。混在一张表里就得在时序集合上
 *   做更新操作，既低效又违背时序集合的设计初衷。
 *   —— 这是 CQRS（读写模型分离）思想的简化实践：写模型与读模型分开。
 *
 * 【为什么要有这一层】
 *   改造前只有"下发"这个动作，没有"任务"这个实体 —— taskId 只存在于 Kafka 消息里，
 *   发完即消失，既查不到执行历史，也没法做状态跟踪与超时兜底。
 *
 * 对应需求：UC-12（创建并下发）、UC-13（查看执行过程与历史）。
 */
@Document("task")
public class TaskDoc {

    // ---------- 任务状态常量（状态机见 TaskService）----------
    /** 草稿：已建单未下发 */
    public static final String STATUS_CREATED    = "CREATED";
    /** 已下发：指令已进 Kafka，但还没收到设备回执（Kafka 发送成功 ≠ 设备收到） */
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    /** 执行中：已收到 STARTED 回执，确认设备真的在跑了 */
    public static final String STATUS_RUNNING    = "RUNNING";
    public static final String STATUS_FINISHED   = "FINISHED";
    /** 失败：回执超时，或设备回执了 FAILED */
    public static final String STATUS_FAILED     = "FAILED";
    public static final String STATUS_CANCELLED  = "CANCELLED";

    // ---------- 任务类型 ----------
    public static final String TYPE_ROUTINE = "ROUTINE";   // 例行空中巡查
    public static final String TYPE_SPECIAL = "SPECIAL";   // 专项任务
    public static final String TYPE_REVIEW  = "REVIEW";    // 地面抵近复核（告警触发）

    @Id
    private String taskId;          // TASK-20260917-0001（由 SequenceService 原子生成，全局唯一）

    private String taskType;        // ROUTINE / SPECIAL / REVIEW
    private String deviceNo;        // 目标设备
    private String deviceType;      // DRONE / ROBOT_DOG（冗余存一份，便于按类型筛选而不用回表）
    private String area;            // 作业区域
    private String route;           // 路线描述（可空）
    private String description;     // 任务说明

    private String status;          // 六态，见上方常量
    private Integer progress;       // 0~100，来自设备回执

    private String createBy;        // 创建人（取自 JWT token）
    private Long createTime;
    private Long dispatchTime;      // 最近一次下发时间
    private Long finishTime;

    private String msgId;           // 最近一次下发的指令 msgId（重发时覆盖，便于追溯）
    private Integer dispatchCount;  // 下发次数（重发计数，需求 UC-12 备选流"可重发"）

    private String alarmId;         // 来源告警（仅 REVIEW 类任务有值）
    private String remark;          // 最后一条回执备注 / 取消原因 / 失败原因

    public TaskDoc() {
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public Long getCreateTime() { return createTime; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }

    public Long getDispatchTime() { return dispatchTime; }
    public void setDispatchTime(Long dispatchTime) { this.dispatchTime = dispatchTime; }

    public Long getFinishTime() { return finishTime; }
    public void setFinishTime(Long finishTime) { this.finishTime = finishTime; }

    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }

    public Integer getDispatchCount() { return dispatchCount; }
    public void setDispatchCount(Integer dispatchCount) { this.dispatchCount = dispatchCount; }

    public String getAlarmId() { return alarmId; }
    public void setAlarmId(String alarmId) { this.alarmId = alarmId; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    /** 是否处于"还没结束"的状态（用于超时检测与"进行中"筛选） */
    public boolean isActive() {
        return STATUS_DISPATCHED.equals(status) || STATUS_RUNNING.equals(status);
    }

    @Override
    public String toString() {
        return "TaskDoc{taskId='" + taskId + "', deviceNo='" + deviceNo
                + "', type='" + taskType + "', status='" + status + "', progress=" + progress + '}';
    }
}
