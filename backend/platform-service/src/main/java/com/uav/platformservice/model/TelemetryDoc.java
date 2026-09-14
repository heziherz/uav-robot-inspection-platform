package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 设备轨迹文档（MongoDB 时序集合 telemetry）。
 * 注意：这是“追加型”时序数据，不是最新值 —— 每条消息一条文档，永不覆盖。
 */
@Document("telemetry")
public class TelemetryDoc {

    @Id
    private String id;              // 由 MongoDB 自动生成
    private String deviceNo;        // metaField（同设备归组）
    private Double lat;
    private Double lng;
    private Double altitude;
    private Double speed;
    private Double heading;
//    private Long eventTime;         // timeField（设备产生时间）
//    private Long ingestTime;        // 平台接收时间（双时间戳的另一半）
    /** timeField：时序集合要求必须是 Date 类型 */
    private Date eventTime;
    /** 平台接收时间（不是 timeField，可以用 long） */
    private Long ingestTime;
    public TelemetryDoc() {
    }

    // --- getter / setter ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public Double getAltitude() { return altitude; }
    public void setAltitude(Double altitude) { this.altitude = altitude; }

    public Double getSpeed() { return speed; }
    public void setSpeed(Double speed) { this.speed = speed; }

    public Double getHeading() { return heading; }
    public void setHeading(Double heading) { this.heading = heading; }

    public Date getEventTime() { return eventTime; }
    public void setEventTime(Date eventTime) { this.eventTime = eventTime; }

    public long getIngestTime() { return ingestTime; }
    public void setIngestTime(long ingestTime) { this.ingestTime = ingestTime; }
}