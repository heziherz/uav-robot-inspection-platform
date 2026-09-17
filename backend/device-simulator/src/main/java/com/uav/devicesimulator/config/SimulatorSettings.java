package com.uav.devicesimulator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 仿真端全局运行参数。
 *
 * 改造背景（性能压测需要）：
 *   改造前设备数量硬编码在 DeviceSimulatorApplication 的 `i <= 3`，
 *   无法做"6 → 50 → 500 台"的规模扫描实验。这里把所有可调项集中到一个类，
 *   默认值与改造前完全一致（3+3 台），因此不影响原有演示行为。
 */
@Component
public class SimulatorSettings {

    private final int droneCount;
    private final int dogCount;
    private final int schedulerThreads;
    private final boolean logPayload;
    private final boolean writeMediaFiles;

    public SimulatorSettings(
            @Value("${sim.device.drone-count:3}") int droneCount,
            @Value("${sim.device.dog-count:3}") int dogCount,
            @Value("${sim.device.scheduler-threads:0}") int schedulerThreads,
            @Value("${sim.device.log-payload:true}") boolean logPayload,
            @Value("${sim.device.write-media-files:true}") boolean writeMediaFiles) {
        this.droneCount = droneCount;
        this.dogCount = dogCount;
        this.schedulerThreads = schedulerThreads;
        this.logPayload = logPayload;
        this.writeMediaFiles = writeMediaFiles;
    }

    public int getDroneCount() {
        return droneCount;
    }

    public int getDogCount() {
        return dogCount;
    }

    /** 0 表示自动（取 CPU 核数，最少 4） */
    public int getSchedulerThreads() {
        return schedulerThreads;
    }

    /** true = 每条消息打印完整 JSON（演示用）；压测时置 false 避免同步日志拖慢 */
    public boolean isLogPayload() {
        return logPayload;
    }

    /** false = 不真正落盘占位图片（隔离磁盘 IO 瓶颈时使用） */
    public boolean isWriteMediaFiles() {
        return writeMediaFiles;
    }

    public int getTotalDevices() {
        return droneCount + dogCount;
    }
}
