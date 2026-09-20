package com.uav.devicesimulator.device;

import tools.jackson.databind.json.JsonMapper;
import com.uav.devicesimulator.client.PlatformClient;
import com.uav.devicesimulator.config.SimulatorSettings;
import com.uav.devicesimulator.generator.RouteGenerator;
import com.uav.devicesimulator.storage.StorageClient;
import com.uav.devicesimulator.model.AlarmMessage;
import com.uav.devicesimulator.model.CommandMessage;
import com.uav.devicesimulator.model.GpsMessage;
import com.uav.devicesimulator.model.HeartbeatMessage;
import com.uav.devicesimulator.model.TaskAckMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一台“仿真设备”的抽象。
 *
 * 职责划分：
 *   共性（父类）：身份、生命周期、心跳、位置、告警、指令处理与回执
 *   个性（子类）：route() 路线、运动参数、onStarted() 特色任务
 */
public abstract class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Topic 常量（与《仿真数据与消息契约说明》§6 一致） */
    public static final String TOPIC_HEARTBEAT  = "topic_device_heartbeat";
    public static final String TOPIC_GPS        = "topic_device_gps";
    public static final String TOPIC_SENSOR     = "topic_device_sensor";
    public static final String TOPIC_MEDIA_META = "topic_device_media_meta";
    public static final String TOPIC_ALARM      = "topic_device_alarm";
    public static final String TOPIC_TASK_ACK   = "topic_device_task_ack";
    public static final String TOPIC_COMMAND    = "topic_platform_command";   // 下行

    protected final String deviceNo;
    protected final String deviceType;
    protected final KafkaTemplate<String, String> kafkaTemplate;
    protected final int heartbeatSeconds;
    protected final int positionSeconds;

    /** 全局共享调度池（由 SimulatorSchedulerConfig 提供；不再每台设备各建一个 4 线程池） */
    protected final ScheduledExecutorService scheduler;
    /**
     * 设备端存储客户端 —— 影像由设备自己上传到 HDFS，平台不再搬运文件。
     * 消息中只传存储引用（storageRef），不传本地路径。
     */
    protected final StorageClient storageClient;

    /**
     * 平台客户端 —— 上传前先向平台申请授权（平台校验设备身份并决定存储路径）。
     * "平台校验通过后，设备才允许往 HDFS 传"这条规则由它保证。
     */
    protected final PlatformClient platformClient;
    protected final boolean logPayload;
    protected final boolean writeMediaFiles;

    /** 本设备登记的周期任务：stop() 只取消自己的，绝不动共享池 */
    private final List<ScheduledFuture<?>> periodicTasks = new CopyOnWriteArrayList<>();

    protected volatile int battery = 100;
    /** 已执行过的指令 msgId —— 防止重复投递导致重复执行（契约 §8.2 幂等要求） */
    private final java.util.Set<String> processedMsgIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    protected DeviceSimulator(String deviceNo, String deviceType,
                              KafkaTemplate<String, String> kafkaTemplate,
                              ScheduledExecutorService scheduler,
                              SimulatorSettings settings,
                              StorageClient storageClient,
                              PlatformClient platformClient,
                              int heartbeatSeconds, int positionSeconds) {
        this.deviceNo = deviceNo;
        this.deviceType = deviceType;
        this.kafkaTemplate = kafkaTemplate;
        this.scheduler = scheduler;
        this.storageClient = storageClient;
        this.platformClient = platformClient;
        this.logPayload = settings.isLogPayload();
        this.writeMediaFiles = settings.isWriteMediaFiles();
        this.heartbeatSeconds = heartbeatSeconds;
        this.positionSeconds = positionSeconds;
    }

    // ---------- 基本信息 ----------

    /** 供 DeviceManager 索引设备 */
    public String deviceNo() {
        return deviceNo;
    }

    // ---------- 子类必须提供的“个性” ----------

    protected abstract RouteGenerator route();

    protected void onStarted() {
    }

    protected double altitudeMeters() {
        return 0.0;
    }

    protected double speedMps() {
        return 0.0;
    }

    // ---------- 生命周期 ----------

    public void start() {
        log.info("设备 {} ({}) 启动：心跳 {} 秒 / 位置 {} 秒",
                deviceNo, deviceType, heartbeatSeconds, positionSeconds);

        schedule(this::safeSendHeartbeat, 0, heartbeatSeconds);
        schedule(this::safeSendPosition, 1, positionSeconds);

        onStarted();
    }

    /**
     * 停止本设备：只取消自己登记的周期任务。
     * ⚠ 绝不能 scheduler.shutdown() —— 池是全局共享的，那样会把其他设备一起停掉。
     */
    public void stop() {
        periodicTasks.forEach(t -> t.cancel(false));
        periodicTasks.clear();
        log.info("设备 {} 已停止", deviceNo);
    }

    /** 周期任务（父类与子类共用；统一登记，便于 stop() 精确取消） */
    protected void schedule(Runnable task, long initialDelaySeconds, long periodSeconds) {
        periodicTasks.add(scheduler.scheduleAtFixedRate(
                task, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS));
    }

    /** 一次性延迟任务（模拟任务执行过程用；短命任务，不登记） */
    protected void scheduleOnce(Runnable task, long delaySeconds) {
        scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
    }

    // ---------- 心跳 ----------

    private void safeSendHeartbeat() {
        try {
            sendHeartbeat();
        } catch (Exception e) {
            log.error("设备 {} 发送心跳失败: {}", deviceNo, e.getMessage());
        }
    }

    protected void sendHeartbeat() {
        if (battery > 10) {
            battery--;
        }
        long now = System.currentTimeMillis();
        HeartbeatMessage msg = new HeartbeatMessage(
                "MSG-" + deviceNo + "-" + now, deviceNo, deviceType, battery, "ONLINE", now);
        sendToKafka(TOPIC_HEARTBEAT, msg, "心跳已发送");
    }

    // ---------- 位置 ----------

    private void safeSendPosition() {
        try {
            sendPosition();
        } catch (Exception e) {
            log.error("设备 {} 发送位置失败: {}", deviceNo, e.getMessage());
        }
    }

    protected void sendPosition() {
        double[] point = route().next();
        long now = System.currentTimeMillis();
        GpsMessage msg = new GpsMessage(
                "GPS-" + deviceNo + "-" + now, deviceNo,
                point[0], point[1], altitudeMeters(), speedMps(), 95.0, now);
        sendToKafka(TOPIC_GPS, msg, "位置已上报");
    }
    /**
     * 生成一个占位图片文件（真实场景由摄像头产生）。
     * @return 文件的绝对路径，供平台端读取并上传 HDFS
     */
    protected String generatePlaceholderFile(String fileId, int sizeBytes) {
        if (!writeMediaFiles) {
            // 压测模式（sim.device.write-media-files=false）：
            //   500 台设备时落盘约 46 文件/秒，小文件堆积本身就是一个独立瓶颈。
            //   关掉它可以把"磁盘 IO"这项从实验中隔离出去，只测消息链路。
            //   代价：平台端读不到文件，影像上传 HDFS 会失败（预期内，日志会有报错）。
            return "sim-files/" + fileId + ".jpg";
        }
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), "sim-files");
            java.nio.file.Files.createDirectories(dir);

            java.nio.file.Path file = dir.resolve(fileId + ".jpg");
            byte[] data = new byte[sizeBytes];
            new java.util.Random().nextBytes(data);
            java.nio.file.Files.write(file, data);

            return file.toAbsolutePath().toString();   // 绝对路径
        } catch (Exception e) {
            log.error("[{}] 生成占位文件失败: {}", deviceNo, e.getMessage());
            return null;
        }
    }
    // ---------- 告警 ----------

    protected void sendAlarm(String alarmType, String level, String description) {
        sendAlarm(alarmType, level, description, null);
    }

    protected void sendAlarm(String alarmType, String level, String description, String mediaFileId) {
        long now = System.currentTimeMillis();
        double[] point = route().current();
        AlarmMessage msg = new AlarmMessage(
                "ALM-MSG-" + deviceNo + "-" + now,
                deviceNo,
                "ALM-" + deviceNo + "-" + now,
                alarmType, level,
                point[0], point[1],
                mediaFileId, description, now);
        sendToKafka(TOPIC_ALARM, msg, "告警已上报");
    }

    // ---------- 下行指令：执行并回执 ----------

    /**
     * 收到平台下发的任务指令：打印 → 模拟执行 → 分阶段回执。
     * 真实设备不会瞬间完成，所以用延迟任务模拟“开始 / 进行中 / 完成”。
     */
    public void handleCommand(CommandMessage cmd) {
        // 幂等保护：同一条指令（msgId）只执行一次
        if (!processedMsgIds.add(cmd.msgId())) {
            log.warn("[{}] 指令 {} 已执行过，忽略重复投递", deviceNo, cmd.msgId());
            return;
        }
        log.info("[{}] 收到任务指令: taskId={}, taskType={}, area={}",
                deviceNo, cmd.taskId(), cmd.taskType(), cmd.area());

        scheduleOnce(() -> sendTaskAck(cmd.taskId(), "STARTED", 0, "任务开始执行"), 1);
        scheduleOnce(() -> sendTaskAck(cmd.taskId(), "PROGRESS", 50, "执行中"), 4);
        scheduleOnce(() -> sendTaskAck(cmd.taskId(), "FINISHED", 100, "任务执行完成"), 8);
    }

    protected void sendTaskAck(String taskId, String stage, int progress, String remark) {
        long now = System.currentTimeMillis();
        TaskAckMessage msg = new TaskAckMessage(
                "ACK-" + deviceNo + "-" + now, deviceNo, taskId, stage, progress, remark, now);
        sendToKafka(TOPIC_TASK_ACK, msg, "任务回执已发送");
    }

    // ---------- 统一发送 ----------

    protected void sendToKafka(String topic, Object payload, String logTag) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            kafkaTemplate.send(topic, deviceNo, json);
            // 压测时每条消息打印完整 JSON 会成为同步 I/O 瓶颈（500 台约 430 行/秒），
            // 由 sim.device.log-payload=false 关闭；默认 true 保持原有演示输出不变。
            if (logPayload) {
                log.info("[{}] {}: {}", deviceNo, logTag, json);
            } else {
                log.debug("[{}] {}", deviceNo, logTag);
            }
        } catch (Exception e) {
            log.error("[{}] {} 失败: {}", deviceNo, logTag, e.getMessage());
        }
    }
}