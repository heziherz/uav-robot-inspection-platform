package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/** 传感器读数（MongoDB 时序集合 sensor_data）—— 追加型数据，不覆盖 */
@Document("sensor_data")
public class SensorDoc {

    @Id
    private String id;
    private String deviceNo;        // metaField
    private Double temperature;
    private Double humidity;
    private Double gasValue;
    private Double deviceTemp;
    /** timeField：时序集合要求必须是 Date 类型 */
    private Date eventTime;
    private Long ingestTime;        // 平台接收时间

    public SensorDoc() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }

    public Double getGasValue() { return gasValue; }
    public void setGasValue(Double gasValue) { this.gasValue = gasValue; }

    public Double getDeviceTemp() { return deviceTemp; }
    public void setDeviceTemp(Double deviceTemp) { this.deviceTemp = deviceTemp; }

    public Date getEventTime() { return eventTime; }
    public void setEventTime(Date eventTime) { this.eventTime = eventTime; }

    public Long getIngestTime() { return ingestTime; }
    public void setIngestTime(Long ingestTime) { this.ingestTime = ingestTime; }
}