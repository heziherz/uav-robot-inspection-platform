package com.uav.platformservice.consumer;

import com.uav.platformservice.config.Topics;
import com.uav.platformservice.model.MediaMetaMessage;
import com.uav.platformservice.service.MediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 影像元数据消费者：落 MongoDB + 上传 HDFS。 */
@Component
public class MediaConsumer {

    private static final Logger log = LoggerFactory.getLogger(MediaConsumer.class);

    private final JsonMapper jsonMapper;
    private final MediaService mediaService;

    public MediaConsumer(JsonMapper jsonMapper, MediaService mediaService) {
        this.jsonMapper = jsonMapper;
        this.mediaService = mediaService;
    }

    @KafkaListener(topics = Topics.DEVICE_MEDIA_META)
    public void onMediaMeta(String message) {
        try {
            MediaMetaMessage msg = jsonMapper.readValue(message, MediaMetaMessage.class);
            mediaService.handleMediaMeta(msg);
        } catch (Exception e) {
            log.error("[影像消费] 处理失败: {}", e.getMessage());
        }
    }
}