package com.uav.platformservice.repository;

import com.uav.platformservice.model.AlarmDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AlarmRepository extends MongoRepository<AlarmDoc, String> {

    /** 告警列表（前端用）：按时间倒序 */
    List<AlarmDoc> findTop50ByOrderByEventTimeDesc();

    /** 按处置状态筛（如只看待处理） */
    List<AlarmDoc> findByHandleStatus(String handleStatus);
}