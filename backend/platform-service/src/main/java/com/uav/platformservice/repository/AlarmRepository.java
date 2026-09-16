package com.uav.platformservice.repository;

import com.uav.platformservice.model.AlarmDoc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AlarmRepository extends MongoRepository<AlarmDoc, String> {

    /**
     * 按处置状态分页查询（服务端分页）。
     * 用 Pageable 而不是 findTopN —— 告警量可能上千条，必须靠数据库分页，不能全量返回给前端。
     */
    Page<AlarmDoc> findByHandleStatus(String handleStatus, Pageable pageable);
}