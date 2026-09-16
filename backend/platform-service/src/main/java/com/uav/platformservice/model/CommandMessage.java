package com.uav.platformservice.model;

public record CommandMessage(
        String msgId,
        String taskId,
        String deviceNo,
        String taskType,     // ROUTINE / SPECIAL / REVIEW
        String area,
        String route,
        long issueTime
) {
}
