package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/** 任务执行日志（MongoDB 时序集合 task_log）—— 追加型，记录任务每个阶段的回执 */
@Document("task_log")
public class TaskLogDoc {

    @Id
    private String id;
    private String deviceNo;        // metaField
    private String taskId;
    private String stage;
    private Integer progress;
    private String remark;
    /** timeField：时序集合要求必须是 Date */
    private Date eventTime;
    private Long ingestTime;

    public TaskLogDoc() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public Date getEventTime() { return eventTime; }
    public void setEventTime(Date eventTime) { this.eventTime = eventTime; }

    public Long getIngestTime() { return ingestTime; }
    public void setIngestTime(Long ingestTime) { this.ingestTime = ingestTime; }
}