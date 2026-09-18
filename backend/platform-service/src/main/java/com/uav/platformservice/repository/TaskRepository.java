package com.uav.platformservice.repository;

import com.uav.platformservice.model.TaskDoc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * 任务台账仓储（出站适配器）。
 *
 * 说明：多条件动态查询走 TaskService 里的 MongoTemplate + Criteria
 * （与 AlarmService.queryAlarms 保持同一套模式），这里只放固定条件的方法。
 */
public interface TaskRepository extends MongoRepository<TaskDoc, String> {

    /** 按状态分页（服务端分页，避免全量返回给前端） */
    Page<TaskDoc> findByStatus(String status, Pageable pageable);

    /** 按设备查该设备的历史任务 */
    Page<TaskDoc> findByDeviceNo(String deviceNo, Pageable pageable);

    /** 查所有"未结束"的任务（超时检测用） */
    List<TaskDoc> findByStatusIn(List<String> statuses);

    /** 各状态任务数（统计卡片用） */
    long countByStatus(String status);
}
