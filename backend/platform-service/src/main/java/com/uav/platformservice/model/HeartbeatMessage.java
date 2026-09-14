package com.uav.platformservice.model;

public record HeartbeatMessage(
        String msgId,
        String deviceNo,
        String deviceType,
        int battery,
        String status,
        long eventTime
) {
}
