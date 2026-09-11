package com.uav.devicesimulator.model;

public record HeartbeatMessage(
        String msgId,
        String deviceNo,
        String deviceType,
        int battery,
        String status,
        long eventTime
) {
}
