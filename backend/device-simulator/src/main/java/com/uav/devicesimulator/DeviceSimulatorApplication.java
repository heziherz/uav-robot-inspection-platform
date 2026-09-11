package com.uav.devicesimulator;

import com.uav.devicesimulator.device.DeviceManager;
import com.uav.devicesimulator.device.DeviceSimulator;
import com.uav.devicesimulator.device.DroneSimulator;
import com.uav.devicesimulator.device.RobotDogSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 仿真程序入口。不是 Web 应用（不开端口），进程靠设备里的定时器线程保持存活。
 */
@SpringBootApplication
public class DeviceSimulatorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulatorApplication.class);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DeviceManager deviceManager;

    public static void main(String[] args) {
        SpringApplication.run(DeviceSimulatorApplication.class, args);
    }

    @Override
    public void run(String... args) {
        // 多设备并发仿真：3 台无人机 + 3 台机器狗
        for (int i = 1; i <= 3; i++) {
            startDevice(new DroneSimulator(String.format("UAV-%03d", i), kafkaTemplate));
            startDevice(new RobotDogSimulator(String.format("DOG-%03d", i), kafkaTemplate));
        }
        log.info("仿真程序已启动：3 台无人机 + 3 台机器狗（Ctrl+C 停止）");
    }

    /** 先注册到设备表，再启动（顺序很重要：注册后指令才找得到它） */
    private void startDevice(DeviceSimulator device) {
        deviceManager.register(device);
        device.start();
    }
}