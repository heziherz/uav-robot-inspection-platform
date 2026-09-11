package com.uav.devicesimulator.model;

public record SensorMessage(
        String msgId,//传感器id
        String deviceNo,//设备编号
        double temperature,//环境温度
        double humidity,//环境湿度
        double gasValue,//气体浓度
        double deviceTemp,//设备温度
        long eventTime//事件发生时间
) {
}
