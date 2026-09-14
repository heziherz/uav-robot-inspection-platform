package com.uav.devicesimulator.device;

import com.uav.devicesimulator.generator.RouteGenerator;
import com.uav.devicesimulator.model.MediaMetaMessage;
import com.uav.devicesimulator.model.SensorMessage;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Random;

/**
 * 机器狗仿真设备。
 * 沿地面路线行走（楼道、围墙一线，速度慢、高度 0）；
 * 特色数据：环境传感器 + 红外图片元数据。
 */
public class RobotDogSimulator extends DeviceSimulator {

    /** 地面行走路线（贴近建筑与围墙，比无人机路线短） */
    private static final double[][] GROUND_ROUTE = {
            {29.5626, 106.5498},
            {29.5629, 106.5501},
            {29.5631, 106.5499},
            {29.5628, 106.5496},
            {29.5625, 106.5494}
    };

    private final RouteGenerator route;
    private final Random random = new Random();

    public RobotDogSimulator(String deviceNo, KafkaTemplate<String, String> kafkaTemplate) {
        super(deviceNo, "ROBOT_DOG", kafkaTemplate, 5, 3); // 心跳 5 秒 / 位置 3 秒
        // 每台机器狗错开作业区域（比无人机更小的偏移）与起点相位
        this.route = RouteGenerator.forDevice(GROUND_ROUTE, deviceNo, 0.0008, 0.0010);
    }

    @Override
    protected RouteGenerator route() {
        return route;
    }

    @Override
    protected double speedMps() {
        return 1.2;    // 地面行走速度（高度用父类默认值 0）
    }

    /** 特色任务：环境传感器 + 红外影像 */
    @Override
    protected void onStarted() {
        schedule(this::sendSensor, 2, 3);      // 每 3 秒采集一次环境数据
        schedule(this::sendMediaMeta, 5, 12);  // 每 12 秒一张红外图
    }

    private void sendSensor() {
        long now = System.currentTimeMillis();
        double temperature = round1(25 + random.nextDouble() * 4 - 2);
        double humidity    = round1(50 + random.nextDouble() * 10 - 5);
        double gasValue    = round2(random.nextDouble() * 5);
        double deviceTemp  = round1(40 + random.nextDouble() * 6 - 3);

        SensorMessage msg = new SensorMessage(
                "SEN-" + deviceNo + "-" + now,
                deviceNo, temperature, humidity, gasValue, deviceTemp, now);
        sendToKafka(TOPIC_SENSOR, msg, "传感器已上报");

        // 数据驱动告警：读数越界才告警
        if (gasValue > 4.0) {
            sendAlarm("ENV", "WARN", "可燃气体浓度超标: " + gasValue + " ppm");
        }
        if (deviceTemp > 42.0) {
            sendAlarm("OVERHEAT", "CRITICAL", "设备温度过高: " + deviceTemp + " ℃");
        }
    }

    private void sendMediaMeta() {
        long now = System.currentTimeMillis();
        String fileId = "IR-" + deviceNo + "-" + now;
        double[] point = route.current();   // 当前位置拍摄
        int size = 1024;
        String localPath = generatePlaceholderFile(fileId, size);   // ← 新增
        MediaMetaMessage msg = new MediaMetaMessage(
                "MED-" + deviceNo + "-" + now,
                deviceNo,
                fileId,
                "INFRARED",
                size,// 约 250KB（模拟）
                localPath,
//                "sim-files/" + fileId + ".jpg",
                point[0],
                point[1],
                now
        );
        sendToKafka(TOPIC_MEDIA_META, msg, "红外影像元数据已上报");
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}