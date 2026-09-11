package com.uav.devicesimulator.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.devicesimulator.generator.RouteGenerator;
import com.uav.devicesimulator.model.AlarmMessage;
import com.uav.devicesimulator.model.CommandMessage;
import com.uav.devicesimulator.model.GpsMessage;
import com.uav.devicesimulator.model.HeartbeatMessage;
import com.uav.devicesimulator.model.TaskAckMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    protected final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    protected volatile int battery = 100;

    protected DeviceSimulator(String deviceNo, String deviceType,
                              KafkaTemplate<String, String> kafkaTemplate,
                              int heartbeatSeconds, int positionSeconds) {
        this.deviceNo = deviceNo;
        this.deviceType = deviceType;
        this.kafkaTemplate = kafkaTemplate;
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

        scheduler.scheduleAtFixedRate(this::safeSendHeartbeat, 0, heartbeatSeconds, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::safeSendPosition, 1, positionSeconds, TimeUnit.SECONDS);

        onStarted();
    }

    public void stop() {
        scheduler.shutdown();
        log.info("设备 {} 已停止", deviceNo);
    }

    /** 周期任务（子类注册特色任务用） */
    protected void schedule(Runnable task, long initialDelaySeconds, long periodSeconds) {
        scheduler.scheduleAtFixedRate(task, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /** 一次性延迟任务（模拟任务执行过程用） */
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
            log.info("[{}] {}: {}", deviceNo, logTag, json);
        } catch (Exception e) {
            log.error("[{}] {} 失败: {}", deviceNo, logTag, e.getMessage());
        }
    }
}