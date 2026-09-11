package com.uav.devicesimulator.model;

public record GpsMessage(
        String msgId,
        String deviceNo,
        double lat,
        double lng,
        double altitude,
        double speed,
        double heading,
        long eventTime
) {
}
