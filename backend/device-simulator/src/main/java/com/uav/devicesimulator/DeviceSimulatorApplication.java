package com.uav.devicesimulator;

import com.uav.devicesimulator.config.SimulatorSettings;
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

import java.util.concurrent.ScheduledExecutorService;

/**
 * 仿真程序入口。不是 Web 应用（不开端口），进程靠共享调度池里的线程保持存活。
 *
 * 设备数量由 sim.device.drone-count / sim.device.dog-count 决定（默认 3 + 3），
 * 因此同一份代码可以跑 6 台，也可以跑 500 台做容量压测。
 */
@SpringBootApplication
public class DeviceSimulatorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulatorApplication.class);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DeviceManager deviceManager;

    /** 全局共享调度池（SimulatorSchedulerConfig 提供），所有设备共用 */
    @Autowired
    private ScheduledExecutorService deviceScheduler;

    @Autowired
    private SimulatorSettings settings;

    public static void main(String[] args) {
        SpringApplication.run(DeviceSimulatorApplication.class, args);
    }

    @Override
    public void run(String... args) {
        int drones = settings.getDroneCount();
        int dogs = settings.getDogCount();

        for (int i = 1; i <= drones; i++) {
            startDevice(new DroneSimulator(String.format("UAV-%03d", i),
                    kafkaTemplate, deviceScheduler, settings));
        }
        for (int i = 1; i <= dogs; i++) {
            startDevice(new RobotDogSimulator(String.format("DOG-%03d", i),
                    kafkaTemplate, deviceScheduler, settings));
        }

        log.info("仿真程序已启动：{} 台无人机 + {} 台机器狗（共 {} 台，Ctrl+C 停止）",
                drones, dogs, drones + dogs);

        if (drones + dogs == 0) {
            log.warn("设备数为 0：共享调度池里没有任何任务，JVM 会立即退出。"
                    + "请检查 sim.device.drone-count / sim.device.dog-count");
        }
    }

    /** 先注册到设备表，再启动（顺序很重要：注册后指令才找得到它） */
    private void startDevice(DeviceSimulator device) {
        deviceManager.register(device);
        device.start();
    }
}