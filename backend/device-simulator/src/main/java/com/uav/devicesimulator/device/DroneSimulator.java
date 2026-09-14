package com.uav.devicesimulator.device;

import com.uav.devicesimulator.generator.RouteGenerator;
import com.uav.devicesimulator.model.GpsMessage;
import com.uav.devicesimulator.model.MediaMetaMessage;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Random;

/**
 * 无人机仿真设备。
 * 特色数据：沿预置园区路线“飞行”，每次上报一个 GPS 坐标。
 */
public class DroneSimulator extends DeviceSimulator {

    /** 预置的园区巡逻路线（经纬度，可替换为真实园区坐标） */
    private static final double[][] AIR_ROUTE = {
            {29.5621, 106.5503},
            {29.5632, 106.5518},
            {29.5645, 106.5506},
            {29.5638, 106.5489},
            {29.5624, 106.5495}
    };
    private final RouteGenerator route;
    public DroneSimulator(String deviceNo, KafkaTemplate<String, String> kafkaTemplate) {
        super(deviceNo, "DRONE", kafkaTemplate, 5, 2); // 心跳 5 秒 / 位置 2 秒
        // 每台无人机错开作业区域（约 160m × 180m）与起点相位
        this.route = RouteGenerator.forDevice(AIR_ROUTE, deviceNo, 0.0015, 0.0018);
    }

    @Override
    protected RouteGenerator route() {
        return route;
    }

    @Override
    protected double altitudeMeters() {
        return 60.0;    // 飞行高度
    }

    @Override
    protected double speedMps() {
        return 8.5;     // 飞行速度
    }

    /** 特色任务：每 10 秒上报一条航拍图片元数据 */
    @Override
    protected void onStarted() {
        schedule(this::sendMediaMeta, 3, 10);
    }

    private final Random random = new Random();   // ← 新增字段

    private void sendMediaMeta() {
        long now = System.currentTimeMillis();
        String fileId = "IMG-" + deviceNo + "-" + now;
        double[] point = route.current();
        int size = 2048;
        String localPath = generatePlaceholderFile(fileId, size);   // ← 新增：真的生成文件
        MediaMetaMessage msg = new MediaMetaMessage(
                "MED-" + deviceNo + "-" + now,
                deviceNo,
                fileId,
                "JPG",
                size,
                localPath,// ← 改为绝对路径
//                "sim-files/" + fileId + ".jpg",
                point[0],
                point[1],
                now
        );
        sendToKafka(TOPIC_MEDIA_META, msg, "航拍影像元数据已上报");

        // 模拟“图像识别发现可疑目标”：15% 概率触发告警
        if (random.nextDouble() < 0.15) {
            String type = random.nextBoolean() ? "INTRUSION" : "SUSPICIOUS";
            String desc = "INTRUSION".equals(type) ? "航拍发现区域入侵" : "航拍发现可疑目标";
            sendAlarm(type, "WARN", desc, fileId);   // 带上证据影像 fileId
        }
    }
}