package com.uav.platformservice.controller;

import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备接口。
 *   读路径：列表 / 详情（前端态势、设备管理页）
 *   写路径：新增 / 编辑 / 停用（UC-21 设备台账维护，管理员操作）
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

    /** 新增设备（管理员登记台账，可先于设备实际上线） */
    @PostMapping
    public DeviceStatus create(@RequestBody DeviceStatus device) {
        return deviceService.createDevice(device);
    }

    /** 编辑设备台账（只改档案字段） */
    @PutMapping("/{deviceNo}")
    public DeviceStatus update(@PathVariable String deviceNo, @RequestBody DeviceStatus patch) {
        return deviceService.updateDevice(deviceNo, patch);
    }

    /** 停用设备（逻辑删除：保留台账，可重新启用） */
    @PutMapping("/{deviceNo}/disable")
    public DeviceStatus disable(@PathVariable String deviceNo) {
        return deviceService.disableDevice(deviceNo);
    }

    /** 重新启用设备 */
    @PutMapping("/{deviceNo}/enable")
    public DeviceStatus enable(@PathVariable String deviceNo) {
        return deviceService.enableDevice(deviceNo);
    }

    /** 删除设备（物理删除台账；历史轨迹/告警数据保留） */
    @DeleteMapping("/{deviceNo}")
    public java.util.Map<String, Object> delete(@PathVariable String deviceNo) {
        deviceService.deleteDevice(deviceNo);
        return java.util.Map.of("deleted", deviceNo);
    }
}
