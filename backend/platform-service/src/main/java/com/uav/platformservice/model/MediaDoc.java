package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 影像元数据（MongoDB 集合 media_meta）—— 文件本体在 HDFS，这里只存元数据。
 *
 * 【字段设计说明】
 *   存储引用字段命名为 storageRef 而非 hdfsPath，是为了**不把平台绑死在 HDFS 上**。
 *   若将来更换为对象存储（S3 / OSS），只需替换设备端的存储客户端，
 *   平台侧的字段与代码无需改动。
 *
 * 【为什么不再有 localPath】
 *   改造前本类存有 localPath（设备本地绝对路径），平台据此去读文件上传 HDFS ——
 *   这隐含了"设备与平台共享文件系统"这一不成立的前提，且把设备端的环境信息
 *   泄漏进了平台数据模型。改造后由设备自行上传，平台只存存储引用。
 */
@Document("media_meta")
public class MediaDoc {

    @Id
    private String fileId;          // 影像唯一标识（与存储文件名对应）
    private String deviceNo;
    private String fileType;
    private Long size;
    private String storageRef;      // 存储引用（设备上传后给出，平台据此读取文件）
    private Double lat;
    private Double lng;
    private Long eventTime;
    private Long ingestTime;
    private Boolean uploaded;       // 是否已归档（设备上传成功才会发消息，故入库即为 true）

    public MediaDoc() {
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String storageRef) { this.storageRef = storageRef; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public Long getEventTime() { return eventTime; }
    public void setEventTime(Long eventTime) { this.eventTime = eventTime; }

    public Long getIngestTime() { return ingestTime; }
    public void setIngestTime(Long ingestTime) { this.ingestTime = ingestTime; }

    public Boolean getUploaded() { return uploaded; }
    public void setUploaded(Boolean uploaded) { this.uploaded = uploaded; }
}
