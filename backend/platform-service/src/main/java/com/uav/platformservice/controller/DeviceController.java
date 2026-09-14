package com.uav.platformservice.controller;

import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备接口（读路径）。
 * 供前端"设备台账/实时态势"页面调用。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 设备列表 */
    @GetMapping
    public List<DeviceStatus> list() {
        return deviceService.listAll();
    }

    /** 单台设备详情 */
    @GetMapping("/{deviceNo}")
    public DeviceStatus detail(@PathVariable String deviceNo) {
        return deviceService.find(deviceNo);
    }
}