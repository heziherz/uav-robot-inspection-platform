package com.uav.platformservice.controller;

import com.uav.platformservice.service.MediaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 影像接口（UC-03 / 文件存储管理）。
 *
 * 【数据流】列表查 MongoDB（元数据）→ 预览/下载时按 hdfsPath 从 HDFS 取文件本体。
 * 这正是"元数据与文件分离存储"设计的读路径实现。
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /** 影像列表（分页 + 按设备筛选） */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String deviceNo,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "12") int size) {
        return mediaService.pageMedia(deviceNo, page, size);
    }

    /**
     * 预览 / 下载影像文件。
     * mode=inline     → 浏览器直接显示（预览）
     * mode=attachment → 触发下载
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String fileId,
                                           @RequestParam(defaultValue = "inline") String mode) {
        byte[] data = mediaService.loadFile(fileId);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .header("Content-Disposition", mode + "; filename=\"" + fileId + ".jpg\"")
                .body(data);
    }
}
