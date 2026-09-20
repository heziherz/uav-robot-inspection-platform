package com.uav.platformservice.service;

import com.uav.platformservice.common.BusinessException;
import com.uav.platformservice.model.MediaDoc;
import com.uav.platformservice.model.MediaMetaMessage;
import com.uav.platformservice.repository.DeviceStatusRepository;
import com.uav.platformservice.repository.MediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影像元数据服务。
 *
 * 【职责边界 —— 读写分离的两个方向】
 *   写入方向：**由设备负责**（设备自己上传影像到 HDFS，消息中携带 storageRef）。
 *   读取方向：**由平台负责**（前端预览、下载影像，从 storageRef 读取）。
 *
 * 【改造说明】
 *   改造前本服务承担"读本地文件 → 上传 HDFS → 回填路径"的搬运工作，
 *   需要 2 次数据库写 + 3 次 HDFS HTTP，还依赖 Docker 绑定挂载制造出的
 *   "共享文件系统"假象，并靠正则剥离 Windows 路径前缀来绕过环境差异。
 *
 *   改造后只写一次元数据，平台不再搬运任何文件字节。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MediaRepository mediaRepository;
    private final DeviceStatusRepository deviceStatusRepository;
    private final HdfsService hdfsService;

    public MediaService(MediaRepository mediaRepository,
                        DeviceStatusRepository deviceStatusRepository,
                        HdfsService hdfsService) {
        this.mediaRepository = mediaRepository;
        this.deviceStatusRepository = deviceStatusRepository;
        this.hdfsService = hdfsService;
    }

    /**
     * 【设备通道】申请影像上传授权。
     *
     * 调用前，设备身份已由 AuthInterceptor 的"设备通道"分支校验通过。
     * 这里再校验一次**设备是否已注册**——令牌只能证明"调用方知道密钥"，
     * 不能证明"这台设备是合法的"（可能是已停用或已删除的设备）。
     *
     * ★ 返回的 storageRef 由平台决定，设备只是执行者。
     */
    public Map<String, String> authorizeUpload(String deviceNo, String fileId, String fileType) {
        if (deviceNo == null || deviceNo.isBlank()) {
            throw new BusinessException("缺少设备身份");
        }
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException("缺少影像标识 fileId");
        }
        if (!deviceStatusRepository.existsById(deviceNo)) {
            throw new BusinessException("设备未注册: " + deviceNo);
        }

        String storageRef = buildStorageRef(deviceNo, fileId);

        log.info("[上传授权] {} 已获准上传（{}）→ {}", deviceNo, fileType, storageRef);
        return Map.of("storageRef", storageRef);
    }

    /**
     * 处理设备上报的影像元数据。
     *
     * 设备上传成功才会发送本消息，这里再做一道**路径规范校验**：
     * 平台签发的 storageRef 是有固定格式的，若设备不按签发的路径走
     * （自行拼接、写到别的设备目录），则拒绝入库。
     */
    public void handleMediaMeta(MediaMetaMessage msg) {
        // 校验：storageRef 必须与平台为该设备签发的路径规范一致
        String expected = buildStorageRef(msg.deviceNo(), msg.fileId());
        if (!expected.equals(msg.storageRef())) {
            log.warn("[影像入库] 拒绝 {}：存储引用不合规（期望 {}，实际 {}）",
                    msg.fileId(), expected, msg.storageRef());
            return;
        }

        MediaDoc doc = new MediaDoc();
        doc.setFileId(msg.fileId());
        doc.setDeviceNo(msg.deviceNo());
        doc.setFileType(msg.fileType());
        doc.setSize(msg.size());
        doc.setStorageRef(msg.storageRef());
        doc.setLat(msg.lat());
        doc.setLng(msg.lng());
        doc.setEventTime(msg.eventTime());
        doc.setIngestTime(System.currentTimeMillis());
        doc.setUploaded(true);                   // 设备上传成功才会发消息，故入库即为已归档

        mediaRepository.save(doc);               // 只写一次，无 HDFS 调用
        log.info("[影像入库] {} 已归档: {}", doc.getFileId(), doc.getStorageRef());
    }

    /**
     * 存储路径规范：/uav/media/{设备编号}/{yyyyMMdd}/{fileId}.jpg
     * 签发与校验共用此方法，保证两处规则完全一致。
     */
    private String buildStorageRef(String deviceNo, String fileId) {
        return "/uav/media/" + deviceNo + "/"
                + LocalDate.now().format(DAY) + "/" + fileId + ".jpg";
    }

    // ==================== 影像管理（读路径 · UC-03 / 文件存储管理） ====================

    /**
     * 分页查询影像元数据（影像管理页）。支持按设备筛选，返回 { total, items }。
     */
    public Map<String, Object> pageMedia(String deviceNo, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size,
                Sort.by(Sort.Direction.DESC, "eventTime"));

        Page<MediaDoc> result = (deviceNo == null || deviceNo.isBlank())
                ? mediaRepository.findAll(pageable)
                : mediaRepository.findByDeviceNo(deviceNo, pageable);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("items", result.getContent());
        return resp;
    }

    /**
     * 读取影像文件内容（供前端预览 / 下载）。
     * 元数据在 MongoDB、文件本体在 HDFS —— 这里把两者串起来，业务层不感知存储细节。
     */
    public byte[] loadFile(String fileId) {
        MediaDoc doc = mediaRepository.findById(fileId).orElse(null);
        if (doc == null) {
            log.warn("[影像读取] 元数据不存在: {}", fileId);
            return null;
        }
        if (doc.getStorageRef() == null) {
            log.warn("[影像读取] 文件尚未归档: {}", fileId);
            return null;
        }
        return hdfsService.download(doc.getStorageRef());
    }
}
