package com.uav.platformservice.repository;

import com.uav.platformservice.model.SensorDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SensorRepository extends MongoRepository<SensorDoc, String> {

    /** 查某设备最近的读数（按时间倒序） */
    List<SensorDoc> findTop100ByDeviceNoOrderByEventTimeDesc(String deviceNo);
}