package com.uav.platformservice.repository;

import com.uav.platformservice.model.DeviceStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * 设备状态数据访问层。
 * 继承 MongoRepository 后，save / findById / findAll 等自动可用。
 */
public interface DeviceStatusRepository extends MongoRepository<DeviceStatus, String> {
}
