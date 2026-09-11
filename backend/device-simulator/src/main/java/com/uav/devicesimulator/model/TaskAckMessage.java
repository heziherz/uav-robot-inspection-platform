package com.uav.devicesimulator.model;

public record TaskAckMessage(
        String msgId,
        String deviceNo,
        String taskId,
        String stage,       // STARTED / PROGRESS / FINISHED / FAILED
        int progress,       // 0~100
        String remark,
        long eventTime
) {
}
