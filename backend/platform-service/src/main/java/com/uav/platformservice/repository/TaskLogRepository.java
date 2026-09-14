package com.uav.platformservice.repository;

import com.uav.platformservice.model.TaskLogDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TaskLogRepository extends MongoRepository<TaskLogDoc, String> {

    /** 查某个任务的执行日志（按时间正序，还原执行过程） */
    List<TaskLogDoc> findByTaskIdOrderByEventTimeAsc(String taskId);
}