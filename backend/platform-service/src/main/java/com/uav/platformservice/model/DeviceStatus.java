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

    // ---------- 台账字段（管理员维护；心跳上报只更新上面的运行时字段，不动这些）----------
    private String deviceName;          // 设备名称
    private String model;               // 型号
    private String area;                // 所属区域
    private Boolean enabled;            // 是否启用（停用后不参与任务派发）
    private Long onlineSinceTime;       // 本次上线的起始时间（前端据此计算"在线时长"）

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

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Long getOnlineSinceTime() { return onlineSinceTime; }
    public void setOnlineSinceTime(Long onlineSinceTime) { this.onlineSinceTime = onlineSinceTime; }

    @Override
    public String toString() {
        return "DeviceStatus{deviceNo='" + deviceNo + "', status='" + status
                + "', battery=" + battery + ", heartbeatCount=" + heartbeatCount + '}';
    }
}