package com.uav.platformservice.config;

/**
 * Kafka Topic 常量（与《仿真数据与消息契约说明》§6 一致）。
 * 集中管理，避免字符串散落在各处。
 */
public final class Topics {

    // ---------- 上行：设备 → 平台 ----------
    public static final String DEVICE_HEARTBEAT  = "topic_device_heartbeat";
    public static final String DEVICE_GPS        = "topic_device_gps";
    public static final String DEVICE_SENSOR     = "topic_device_sensor";
    public static final String DEVICE_MEDIA_META = "topic_device_media_meta";
    public static final String DEVICE_ALARM      = "topic_device_alarm";
    public static final String DEVICE_TASK_ACK   = "topic_device_task_ack";

    // ---------- 下行：平台 → 设备 ----------
    public static final String PLATFORM_COMMAND  = "topic_platform_command";

    private Topics() {
    }
}
