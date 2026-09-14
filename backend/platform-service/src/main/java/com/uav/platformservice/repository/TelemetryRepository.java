package com.uav.platformservice.repository;

import com.uav.platformservice.model.TelemetryDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TelemetryRepository extends MongoRepository<TelemetryDoc, String> {

    /** 查某设备最近的轨迹（按时间倒序）—— 覆盖最常见的查询场景 */
    List<TelemetryDoc> findTop100ByDeviceNoOrderByEventTimeDesc(String deviceNo);
    /** 查某设备最新的一条位置（地图打点用） */
    List<TelemetryDoc> findTop1ByDeviceNoOrderByEventTimeDesc(String deviceNo);
}