package com.uav.heartbeat;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 设备状态文档（MongoDB 集合 device_status）。
 * 一台设备一条文档：以 deviceNo 为主键，每次心跳覆盖更新 → 保存“最新状态”。
 */
@Document("device_status")
public class DeviceStatus {

    @Id
    private String deviceNo;       // 设备编号（UAV-001 / DOG-001 ...）
    private String deviceType;     // 设备类型：DRONE / ROBOT_DOG
    private Integer battery;       // 电量 0~100
    private String status;         // 在线状态：online / offline / fault
    private Long ts;               // 心跳时间戳（毫秒）

    public DeviceStatus() {
    }

    public DeviceStatus(String deviceNo, String deviceType, Integer battery, String status, Long ts) {
        this.deviceNo = deviceNo;
        this.deviceType = deviceType;
        this.battery = battery;
        this.status = status;
        this.ts = ts;
    }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public Integer getBattery() { return battery; }
    public void setBattery(Integer battery) { this.battery = battery; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getTs() { return ts; }
    public void setTs(Long ts) { this.ts = ts; }

    @Override
    public String toString() {
        return "DeviceStatus{deviceNo='" + deviceNo + "', battery=" + battery
                + ", status='" + status + "', ts=" + ts + '}';
    }
}
