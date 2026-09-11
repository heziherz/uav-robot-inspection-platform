package com.uav.devicesimulator.model;

public record AlarmMessage(
        String msgId,
        String deviceNo,
        String alarmId,        // 告警唯一 ID（幂等键）
        String alarmType,      // INTRUSION / SUSPICIOUS / ENV / OVERHEAT / FAULT / FENCE
        String level,          // INFO / WARN / CRITICAL
        double lat,
        double lng,
        String mediaFileId,    // 关联的证据影像（可为 null）
        String description,
        long eventTime
) {
}
