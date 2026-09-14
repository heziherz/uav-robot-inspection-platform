package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 设备最新状态（MongoDB 集合 device_status）。
 *
 * 注意：心跳是“最新值”语义，不是时序数据 —— 一台设备永远只有一条文档，
 *      每次心跳覆盖更新（这也是它不用时序集合的原因）。
 */
@Document("device_status")
public class DeviceStatus {

    @Id
    private String deviceNo;            // 设备编号即主键
    private String deviceType;          // DRONE / ROBOT_DOG
    private Integer battery;
    private String status;              // ONLINE / OFFLINE / FAULT
    private Long eventTime;             // 设备上报时间（来自心跳）
    private Long firstSeenTime;         // 首次出现时间 = 设备注册时间
    private Long lastHeartbeatTime;     // 平台最后收到心跳的时间
    private Long heartbeatCount;        // 累计心跳数（演示用，证明持续更新）

    public DeviceStatus() {
    }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public Integer getBattery() { return battery; }
    public void setBattery(Integer battery) { this.battery = battery; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getEventTime() { return eventTime; }
    public void setEventTime(Long eventTime) { this.eventTime = eventTime; }

    public Long getFirstSeenTime() { return firstSeenTime; }
    public void setFirstSeenTime(Long firstSeenTime) { this.firstSeenTime = firstSeenTime; }

    public Long getLastHeartbeatTime() { return lastHeartbeatTime; }
    public void setLastHeartbeatTime(Long lastHeartbeatTime) { this.lastHeartbeatTime = lastHeartbeatTime; }

    public Long getHeartbeatCount() { return heartbeatCount; }
    public void setHeartbeatCount(Long heartbeatCount) { this.heartbeatCount = heartbeatCount; }

    @Override
    public String toString() {
        return "DeviceStatus{deviceNo='" + deviceNo + "', status='" + status
                + "', battery=" + battery + ", heartbeatCount=" + heartbeatCount + '}';
    }
}