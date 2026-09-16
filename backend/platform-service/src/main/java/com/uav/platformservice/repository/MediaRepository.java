package com.uav.platformservice.repository;

import com.uav.platformservice.model.MediaDoc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MediaRepository extends MongoRepository<MediaDoc, String> {

    List<MediaDoc> findTop20ByOrderByEventTimeDesc();

    List<MediaDoc> findByUploaded(Boolean uploaded);     // 查上传失败的，便于重传

    /** 按设备分页查询（影像管理页用） */
    Page<MediaDoc> findByDeviceNo(String deviceNo, Pageable pageable);
}
