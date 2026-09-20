package com.uav.platformservice.model;

/**
 * 影像元数据消息（设备 → 平台，上行契约）。
 *
 * 【契约设计原则：传递「标识」而非「位置」】
 *   改造前本消息携带 localPath（设备本地绝对路径），这隐含了一个不成立的前提——
 *   "平台能访问设备的文件系统"。现实中无人机在天上、平台在机房，两者只有网络。
 *
 *   改造后由**设备自行上传影像到存储**，消息中只携带 storageRef（存储引用）。
 *   平台据此引用读取文件，无需知道设备端的任何环境信息。
 *
 *   对应的存储引用形如：/uav/media/UAV-001/20260919/IMG-UAV-001-1789xxxx.jpg
 */
public record MediaMetaMessage(
        String msgId,
        String deviceNo,
        String fileId,       // 影像唯一标识
        String fileType,     // JPG / INFRARED
        long size,           // 文件大小（字节）
        String storageRef,   // 存储引用（设备已上传完成，平台据此读取）
        double lat,
        double lng,
        long eventTime
) {
}
