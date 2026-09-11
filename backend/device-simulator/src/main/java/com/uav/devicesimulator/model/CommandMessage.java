package com.uav.devicesimulator.model;

public record CommandMessage(
        String msgId,
        String taskId,
        String deviceNo,
        String taskType,    // ROUTINE 例行 / SPECIAL 专项 / REVIEW 抵近复核
        String area,        // 巡检区域
        String route,       // 路线（可为 null）
        long issueTime
) {
}
