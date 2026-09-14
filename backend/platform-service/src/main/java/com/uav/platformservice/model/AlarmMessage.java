package com.uav.platformservice.model;

public record AlarmMessage(
        String msgId,
        String deviceNo,
        String alarmId,         // 幂等键
        String alarmType,       // INTRUSION / SUSPICIOUS / ENV / OVERHEAT / FAULT / FENCE
        String level,           // INFO / WARN / CRITICAL
        double lat,
        double lng,
        String mediaFileId,     // 关联证据影像（可为 null）
        String description,
        long eventTime
) {
}
