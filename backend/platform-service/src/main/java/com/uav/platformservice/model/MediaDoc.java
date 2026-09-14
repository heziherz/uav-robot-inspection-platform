package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** 影像元数据（MongoDB 集合 media_meta）—— 文件本体在 HDFS，这里只存元数据 */
@Document("media_meta")
public class MediaDoc {

    @Id
    private String fileId;          // 与 HDFS 文件名关联的键
    private String deviceNo;
    private String fileType;
    private Long size;
    private String localPath;       // 仿真端本地路径
    private String hdfsPath;        // 上传成功后的 HDFS 路径
    private Double lat;
    private Double lng;
    private Long eventTime;
    private Long ingestTime;
    private Boolean uploaded;       // 是否已成功上传 HDFS

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

    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }

    public String getHdfsPath() { return hdfsPath; }
    public void setHdfsPath(String hdfsPath) { this.hdfsPath = hdfsPath; }

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