package com.uav.platformservice.model;

public record MediaMetaMessage(
        String msgId,
        String deviceNo,
        String fileId,
        String fileType,
        long size,
        String localPath,
        double lat,
        double lng,
        long eventTime
) {
}
