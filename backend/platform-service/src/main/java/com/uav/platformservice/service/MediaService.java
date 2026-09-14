package com.uav.platformservice.service;

import com.uav.platformservice.model.MediaDoc;
import com.uav.platformservice.model.MediaMetaMessage;
import com.uav.platformservice.repository.MediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 影像元数据服务：把“元数据 + 文件”两条路都走完
 *   ① 元数据 → MongoDB
 *   ② 文件 → HDFS（成功后回填 hdfsPath）
 */
@Service
public class MediaService {
    @org.springframework.beans.factory.annotation.Value("${sim.files.dir:}")
    private String simFilesDir;
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaRepository mediaRepository;
    private final HdfsService hdfsService;

    public MediaService(MediaRepository mediaRepository, HdfsService hdfsService) {
        this.mediaRepository = mediaRepository;
        this.hdfsService = hdfsService;
    }

    public void handleMediaMeta(MediaMetaMessage msg) {
        MediaDoc doc = new MediaDoc();
        doc.setFileId(msg.fileId());
        doc.setDeviceNo(msg.deviceNo());
        doc.setFileType(msg.fileType());
        doc.setSize(msg.size());
        doc.setLocalPath(msg.localPath());
        doc.setLat(msg.lat());
        doc.setLng(msg.lng());
        doc.setEventTime(msg.eventTime());
        doc.setIngestTime(System.currentTimeMillis());
        doc.setUploaded(false);

        // ① 先落库（保证元数据不丢）
        mediaRepository.save(doc);

        // 容器模式下：消息里是宿主机绝对路径，容器里读不到 → 用挂载目录 + 文件名重新拼。
        // 注意：容器是 Linux，Paths.get() 认不出 Windows 反斜杠（会把整串当文件名），
        //      因此这里用正则剥掉所有目录前缀，兼容 \ 与 / 两种分隔符。
        String fileName = msg.localPath() == null
                ? null
                : msg.localPath().replaceAll("^.*[\\\\/]", "");
        String realPath = (simFilesDir == null || simFilesDir.isBlank())
                ? msg.localPath()                       // 本地模式：直接用原路径
                : simFilesDir + "/" + fileName;         // 容器模式：/app/sim-files/xxx.jpg
        // ② 再上传 HDFS，成功后回填
        if (msg.localPath() != null && hdfsService.upload(realPath, msg.deviceNo(), msg.fileId())) {
            doc.setHdfsPath("/uav/media/" + msg.deviceNo() + "/"
                    + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + "/" + msg.fileId() + ".jpg");
            doc.setUploaded(true);
            mediaRepository.save(doc);      // 回填 hdfsPath
            log.info("[影像入库] {} 已归档: {}", doc.getFileId(), doc.getHdfsPath());
        } else {
            log.warn("[影像入库] {} 上传 HDFS 失败，元数据已保存待重传", doc.getFileId());
        }
    }
}