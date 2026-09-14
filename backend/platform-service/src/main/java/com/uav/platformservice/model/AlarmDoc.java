package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** 告警记录（MongoDB 集合 alarm）—— 普通集合，因为要更新处置状态 */
@Document("alarm")
public class AlarmDoc {

    @Id
    private String alarmId;         // 用 alarmId 作主键（幂等）
    private String deviceNo;
    private String alarmType;
    private String level;
    private Double lat;
    private Double lng;
    private String mediaFileId;
    private String description;
    private Long eventTime;         // 设备产生时间
    private Long ingestTime;        // 平台接收时间
    private String handleStatus;    // PENDING（待处理）
    private String handleBy;        // 处置人（后续用）
    private Long handleTime;        // 处置时间

    public AlarmDoc() {
    }

    public String getAlarmId() { return alarmId; }
    public void setAlarmId(String alarmId) { this.alarmId = alarmId; }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public String getAlarmType() { return alarmType; }
    public void setAlarmType(String alarmType) { this.alarmType = alarmType; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public String getMediaFileId() { return mediaFileId; }
    public void setMediaFileId(String mediaFileId) { this.mediaFileId = mediaFileId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getEventTime() { return eventTime; }
    public void setEventTime(Long eventTime) { this.eventTime = eventTime; }

    public Long getIngestTime() { return ingestTime; }
    public void setIngestTime(Long ingestTime) { this.ingestTime = ingestTime; }

    public String getHandleStatus() { return handleStatus; }
    public void setHandleStatus(String handleStatus) { this.handleStatus = handleStatus; }

    public String getHandleBy() { return handleBy; }
    public void setHandleBy(String handleBy) { this.handleBy = handleBy; }

    public Long getHandleTime() { return handleTime; }
    public void setHandleTime(Long handleTime) { this.handleTime = handleTime; }
}