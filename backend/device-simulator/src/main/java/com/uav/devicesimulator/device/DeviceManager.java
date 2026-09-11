package com.uav.devicesimulator.device;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备注册表：保存所有正在运行的仿真设备。
 * 作用：指令消费者（Spring Bean）拿到指令后，按 deviceNo 找到对应设备实例。
 */
@Component
public class DeviceManager {

    private final Map<String, DeviceSimulator> devices = new ConcurrentHashMap<>();

    /** 注册一台设备 */
    public void register(DeviceSimulator device) {
        devices.put(device.deviceNo(), device);
    }

    /** 按设备编号查找（找不到返回 null） */
    public DeviceSimulator find(String deviceNo) {
        return devices.get(deviceNo);
    }

    /** 全部在线设备 */
    public Collection<DeviceSimulator> all() {
        return devices.values();
    }
}