package com.uav.heartbeat;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * 设备状态数据的访问层（Spring Data MongoDB）。
 * 继承 MongoRepository 后，save/findById/delete 等常用操作自动可用。
 */
public interface DeviceStatusRepository extends MongoRepository<DeviceStatus, String> {
    // 需要按状态查询时再加方法，例如：List<DeviceStatus> findByStatus(String status);
}
