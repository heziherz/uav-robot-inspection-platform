package com.uav.devicesimulator.model;

public record MediaMetaMessage(
        String msgId,
        String deviceNo,
        String fileId,       // 影像唯一 ID（与文件路径关联）
        String fileType,     // JPG / INFRARED
        long size,           // 文件大小（字节）
        String localPath,    // 仿真端本地存放路径（业务服务据此上传 HDFS）
        double lat,
        double lng,
        long eventTime
) {
}
