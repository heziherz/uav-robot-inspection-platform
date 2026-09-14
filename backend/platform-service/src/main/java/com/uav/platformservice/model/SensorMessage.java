package com.uav.platformservice.model;

public record SensorMessage(
        String msgId,
        String deviceNo,
        double temperature,
        double humidity,
        double gasValue,
        double deviceTemp,
        long eventTime
) {
}
