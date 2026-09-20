package com.uav.platformservice.controller;

import com.uav.platformservice.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 影像接口（UC-03 / 文件存储管理）。
 *
 * 【数据流】列表查 MongoDB（元数据）→ 预览/下载时按 storageRef 从 HDFS 取文件本体。
 *          注意：影像的**写入由设备自己完成**，平台侧只负责读取。
 * 这正是"元数据与文件分离存储"设计的读路径实现。
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * 【设备通道】申请影像上传授权。
     *
     * 设备先取得授权，才允许往 HDFS 传影像。
     * 认证走设备令牌（HMAC），由 AuthInterceptor 的"设备通道"分支完成，
     * 因此这里拿到的 currentDevice 是**已经校验过身份**的设备编号。
     *
     * ★ 返回的 storageRef 由【平台】决定，设备只是执行者 ——
     *   这样存储路径规则完全掌握在平台手里，而非由设备自行拼接。
     */
    @PostMapping("/upload-auth")
    public Map<String, String> authorizeUpload(@RequestBody Map<String, String> body,
                                               HttpServletRequest request) {
        String deviceNo = (String) request.getAttribute("currentDevice");
        return mediaService.authorizeUpload(deviceNo, body.get("fileId"), body.get("fileType"));
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
