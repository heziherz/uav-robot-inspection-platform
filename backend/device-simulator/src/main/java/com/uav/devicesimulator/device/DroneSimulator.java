package com.uav.devicesimulator.device;

import com.uav.devicesimulator.client.PlatformClient;
import com.uav.devicesimulator.config.SimulatorSettings;
import com.uav.devicesimulator.generator.RouteGenerator;
import com.uav.devicesimulator.model.GpsMessage;
import com.uav.devicesimulator.model.MediaMetaMessage;
import com.uav.devicesimulator.storage.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 无人机仿真设备。
 * 特色数据：沿预置园区路线“飞行”，每次上报一个 GPS 坐标。
 */
public class DroneSimulator extends DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DroneSimulator.class);

    /** 预置的园区巡逻路线（经纬度，可替换为真实园区坐标） */
    private static final double[][] AIR_ROUTE = {
            {29.5621, 106.5503},
            {29.5632, 106.5518},
            {29.5645, 106.5506},
            {29.5638, 106.5489},
            {29.5624, 106.5495}
    };
    private final RouteGenerator route;
    public DroneSimulator(String deviceNo, KafkaTemplate<String, String> kafkaTemplate,
                          ScheduledExecutorService scheduler, SimulatorSettings settings,
                          StorageClient storageClient, PlatformClient platformClient) {
        super(deviceNo, "DRONE", kafkaTemplate, scheduler, settings,
                storageClient, platformClient, 5, 2); // 心跳 5 秒 / 位置 2 秒
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

        // ① 生成影像（设备本地暂存）
        String localPath = generatePlaceholderFile(fileId, size);

        // ② 向平台申请上传授权
        //    平台在此校验设备身份，并【决定影像的存储路径】—— 设备只是执行者
        String storageRef = platformClient.requestUploadAuth(deviceNo, fileId, "JPG");
        if (storageRef == null) {
            log.warn("[{}] 未获上传授权，本次跳过上报", deviceNo);
            return;
        }

        // ③ 按平台签发的路径上传
        if (!storageClient.upload(localPath, storageRef)) {
            log.warn("[{}] 影像上传失败，本次跳过上报", deviceNo);
            return;                          // 上传失败则不发消息，避免"有元数据、无文件"
        }

        // ④ 发消息：只带存储引用，不含任何本地路径
        MediaMetaMessage msg = new MediaMetaMessage(
                "MED-" + deviceNo + "-" + now,
                deviceNo,
                fileId,
                "JPG",
                size,
                storageRef,
                point[0],
                point[1],
                now
        );
        sendToKafka(TOPIC_MEDIA_META, msg, "航拍影像元数据已上报");

        // 模拟“图像识别发现可疑目标”：约 1% 概率触发告警
        if (random.nextDouble() < 0.01) {
            String type = random.nextBoolean() ? "INTRUSION" : "SUSPICIOUS";
            String desc = "INTRUSION".equals(type) ? "航拍发现区域入侵" : "航拍发现可疑目标";
            sendAlarm(type, "WARN", desc, fileId);   // 带上证据影像 fileId
        }
    }
}