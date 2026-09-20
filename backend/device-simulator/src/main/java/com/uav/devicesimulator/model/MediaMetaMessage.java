package com.uav.devicesimulator.model;

/**
 * 影像元数据消息（设备 → 平台，上行契约）。
 *
 * 【与平台侧 MediaMetaMessage 保持字段一致 —— 这是跨系统的约定】
 *
 * 【契约设计原则：传递「标识」而非「位置」】
 *   设备自行上传影像到存储后，消息中只携带 storageRef（存储引用），
 *   不再携带本地路径。平台据此引用读取文件，无需感知设备端环境。
 */
public record MediaMetaMessage(
        String msgId,
        String deviceNo,
        String fileId,       // 影像唯一标识
        String fileType,     // JPG / INFRARED
        long size,           // 文件大小（字节）
        String storageRef,   // 存储引用（设备已上传完成）
        double lat,
        double lng,
        long eventTime
) {
}
