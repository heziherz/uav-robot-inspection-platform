package com.uav.platformservice.service;

import com.uav.platformservice.common.BusinessException;
import com.uav.platformservice.common.MessagePublisher;
import com.uav.platformservice.config.Topics;
import com.uav.platformservice.model.CommandMessage;
import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.model.TaskAckMessage;
import com.uav.platformservice.model.TaskDoc;
import com.uav.platformservice.model.TaskLogDoc;
import com.uav.platformservice.repository.DeviceStatusRepository;
import com.uav.platformservice.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检任务业务服务（核心领域层）。
 *
 * 【职责】任务的完整生命周期：创建 → 下发 → 跟踪回执 → 终结（完成/失败/取消）
 *
 * 【状态机】
 *   CREATED ──下发──→ DISPATCHED ──收到 STARTED──→ RUNNING ──收到 FINISHED──→ FINISHED
 *   (草稿)            (已进 Kafka)                 (执行中)
 *                        │                            │
 *                        │ 超时 60s 无回执              │ 超时 10min 未完成
 *                        ▼                            ▼
 *                     FAILED                       FAILED
 *                        │
 *                        └── 人工取消 ──→ CANCELLED
 *
 * 【为什么 DISPATCHED 与 RUNNING 必须分开】
 *   Kafka 发送成功 ≠ 设备收到了。publish() 返回只代表消息进了 Kafka；
 *   设备可能离线、消费者可能没起、指令可能被幂等丢弃。
 *   只有收到 STARTED 回执，才能确认任务真的在执行。
 *   （对应需求 UC-12 备选流"回执超时 → 任务停留执行中"——就是卡在 DISPATCHED）
 *
 * 【与 task_log 的关系】
 *   本服务只管 task 集合（当前状态）；回执流水由 TaskLogService 写 task_log（历史）。
 *   TaskAckConsumer 收到回执时同时喂给两边。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** DISPATCHED 状态超过这个时长还没收到 STARTED → 判定失败 */
    private static final long DISPATCH_TIMEOUT_MS = 60_000L;
    /** RUNNING 状态超过这个时长还没收到 FINISHED → 判定失败 */
    private static final long RUNNING_TIMEOUT_MS = 600_000L;

    private final TaskRepository taskRepository;
    private final DeviceStatusRepository deviceStatusRepository;
    private final SequenceService sequenceService;
    private final MessagePublisher messagePublisher;
    private final MongoTemplate mongoTemplate;

    public TaskService(TaskRepository taskRepository,
                       DeviceStatusRepository deviceStatusRepository,
                       SequenceService sequenceService,
                       MessagePublisher messagePublisher,
                       MongoTemplate mongoTemplate) {
        this.taskRepository = taskRepository;
        this.deviceStatusRepository = deviceStatusRepository;
        this.sequenceService = sequenceService;
        this.messagePublisher = messagePublisher;
        this.mongoTemplate = mongoTemplate;
    }

    // ==================== 创建 ====================

    /**
     * 创建任务。autoDispatch=true 时创建后立即下发（一键完成）。
     *
     * 创建与下发分离的好处：可以先建单后下发（排班场景）、下发失败可重试而不用重建。
     */
    public TaskDoc createTask(TaskDoc req, String currentUser, boolean autoDispatch) {
        if (req.getDeviceNo() == null || req.getDeviceNo().isBlank()) {
            throw new BusinessException("必须指定目标设备");
        }
        if (req.getTaskType() == null || req.getTaskType().isBlank()) {
            req.setTaskType(TaskDoc.TYPE_ROUTINE);
        }

        DeviceStatus device = deviceStatusRepository.findById(req.getDeviceNo()).orElse(null);
        if (device == null) {
            throw new BusinessException("设备不存在: " + req.getDeviceNo());
        }

        TaskDoc task = new TaskDoc();
        task.setTaskId(sequenceService.nextTaskId());      // ← 全局唯一，修掉原来的毫秒碰撞
        task.setTaskType(req.getTaskType());
        task.setDeviceNo(device.getDeviceNo());
        task.setDeviceType(device.getDeviceType());        // 冗余存一份，便于按类型筛选
        task.setArea(req.getArea());
        task.setRoute(req.getRoute());
        task.setDescription(req.getDescription());
        task.setAlarmId(req.getAlarmId());
        task.setStatus(TaskDoc.STATUS_CREATED);
        task.setProgress(0);
        task.setDispatchCount(0);
        task.setCreateBy(currentUser == null ? "system" : currentUser);
        task.setCreateTime(System.currentTimeMillis());

        taskRepository.save(task);
        log.info("[任务] {} 已创建：设备={} 类型={} 创建人={}",
                task.getTaskId(), task.getDeviceNo(), task.getTaskType(), task.getCreateBy());

        if (autoDispatch) {
            try {
                return dispatch(task.getTaskId());
            } catch (IllegalStateException e) {
                // 下发被拒（设备离线/停用等）时任务【已经存成草稿】了。
                // 这一点必须说清楚，否则值班员看到报错会以为任务没建上，
                // 而实际上它就在列表里等着设备上线后手动下发。
                throw new BusinessException(
                        "任务已保存为草稿（" + task.getTaskId() + "），但" + e.getMessage());
            }
        }
        return task;
    }

    /**
     * 供 AlarmService 复用：由告警触发的"指派机器狗抵近复核"任务。
     * 设备选择逻辑收敛在这里，避免告警模块自己再写一套（原来就是重复实现）。
     */
    public TaskDoc createReviewTask(String alarmId, String area, String description, String operator) {
        DeviceStatus dog = pickOnlineDevice("ROBOT_DOG");
        if (dog == null) {
            log.warn("[任务] 无在线机器狗，复核任务未创建: alarmId={}", alarmId);
            return null;
        }
        TaskDoc req = new TaskDoc();
        req.setTaskType(TaskDoc.TYPE_REVIEW);
        req.setDeviceNo(dog.getDeviceNo());
        req.setArea(area);
        req.setDescription(description);
        req.setAlarmId(alarmId);
        return createTask(req, operator, true);
    }

    // ==================== 下发 ====================

    /**
     * 下发任务（也用于重发）。
     *
     * 需求 UC-12 备选流："所选设备已离线 → 系统阻止下发并提示"
     *                   "回执超时 → 可重发或取消"
     */
    public TaskDoc dispatch(String taskId) {
        TaskDoc task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));

        if (TaskDoc.STATUS_FINISHED.equals(task.getStatus())
                || TaskDoc.STATUS_CANCELLED.equals(task.getStatus())) {
            throw new BusinessException("任务已终结，无法下发: " + task.getStatus());
        }

        // ★ 设备可用性校验：离线/停用的设备不允许下发
        DeviceStatus device = deviceStatusRepository.findById(task.getDeviceNo()).orElse(null);
        if (device == null) {
            throw new BusinessException("设备不存在: " + task.getDeviceNo());
        }
        if (!"ONLINE".equals(device.getStatus())) {
            throw new BusinessException("设备离线，无法下发: " + task.getDeviceNo()
                    + "（当前状态 " + device.getStatus() + "）");
        }
        if (Boolean.FALSE.equals(device.getEnabled())) {
            throw new BusinessException("设备已停用，无法下发: " + task.getDeviceNo());
        }

        long now = System.currentTimeMillis();
        int count = (task.getDispatchCount() == null ? 0 : task.getDispatchCount()) + 1;

        // msgId 每次下发都重新生成 —— 重发才能绕过设备侧的幂等去重（这是重发生效的关键）
        String msgId = "CMD-" + task.getTaskId() + "-" + count;

        CommandMessage command = new CommandMessage(
                msgId,
                task.getTaskId(),
                task.getDeviceNo(),
                task.getTaskType(),
                task.getArea() == null ? "" : task.getArea(),
                task.getRoute() == null ? "" : task.getRoute(),
                now);

        // ★ 复用既有统一出口：分区键用 deviceNo，保证同一设备指令有序
        messagePublisher.publish(Topics.PLATFORM_COMMAND, task.getDeviceNo(), command);

        task.setStatus(TaskDoc.STATUS_DISPATCHED);
        task.setMsgId(msgId);
        task.setDispatchCount(count);
        task.setDispatchTime(now);
        task.setFinishTime(null);
        taskRepository.save(task);

        log.info("[任务] {} 已下发（第 {} 次）→ {} | msgId={}",
                task.getTaskId(), count, task.getDeviceNo(), msgId);
        return task;
    }

    // ==================== 回执处理（状态机落地）====================

    /**
     * 把设备回执应用到任务台账上。由 TaskAckConsumer 在收到回执时调用。
     */
    public void applyAck(TaskAckMessage ack) {
        TaskDoc task = taskRepository.findById(ack.taskId()).orElse(null);
        if (task == null) {
            // 任务可能已被清理，或回执来自更早的实现 —— 记日志即可，不能让消费失败
            log.warn("[任务回执] 任务不存在，已忽略: {}", ack.taskId());
            return;
        }

        // 已终结的任务不再接受迟到的回执，避免状态被"回退"
        if (TaskDoc.STATUS_FINISHED.equals(task.getStatus())
                || TaskDoc.STATUS_CANCELLED.equals(task.getStatus())
                || TaskDoc.STATUS_FAILED.equals(task.getStatus())) {
            log.debug("[任务回执] {} 已终结({})，忽略迟到回执 {}",
                    ack.taskId(), task.getStatus(), ack.stage());
            return;
        }

        switch (ack.stage()) {
            case "STARTED" -> {
                task.setStatus(TaskDoc.STATUS_RUNNING);
                task.setProgress(0);
            }
            case "PROGRESS" -> task.setProgress(ack.progress());   // 状态不变，只推进度
            case "FINISHED" -> {
                task.setStatus(TaskDoc.STATUS_FINISHED);
                task.setProgress(100);
                task.setFinishTime(ack.eventTime());
            }
            case "FAILED" -> {
                task.setStatus(TaskDoc.STATUS_FAILED);
                task.setFinishTime(ack.eventTime());
            }
            default -> {
                log.warn("[任务回执] 未知阶段，已忽略: {}", ack.stage());
                return;
            }
        }
        task.setRemark(ack.remark());
        taskRepository.save(task);

        log.info("[任务] {} 回执 {} → 状态={} 进度={}",
                ack.taskId(), ack.stage(), task.getStatus(), task.getProgress());
    }

    // ==================== 取消 ====================

    public TaskDoc cancel(String taskId, String reason) {
        TaskDoc task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));

        if (!task.isActive() && !TaskDoc.STATUS_CREATED.equals(task.getStatus())) {
            throw new BusinessException("任务当前状态不可取消: " + task.getStatus());
        }

        task.setStatus(TaskDoc.STATUS_CANCELLED);
        task.setFinishTime(System.currentTimeMillis());
        task.setRemark(reason == null || reason.isBlank() ? "人工取消" : reason);
        taskRepository.save(task);

        log.info("[任务] {} 已取消：{}", taskId, task.getRemark());
        return task;
    }

    // ==================== 超时兜底 ====================

    /**
     * 超时检测：扫所有未终结的任务，超时未收到回执的判为 FAILED。
     *
     * 【为什么必须有】否则任务会永远卡在 DISPATCHED，前端一直显示"执行中"。
     * 写法参考 DeviceService.detectOfflineDevices()（同样是定时扫、判定、更新）。
     */
    @Scheduled(fixedRate = 30_000)
    public void detectTimeoutTasks() {
        List<TaskDoc> active = taskRepository.findByStatusIn(
                List.of(TaskDoc.STATUS_DISPATCHED, TaskDoc.STATUS_RUNNING));
        if (active.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (TaskDoc task : active) {
            Long since = task.getDispatchTime();
            if (since == null) {
                continue;
            }
            long elapsed = now - since;

            boolean timeout = (TaskDoc.STATUS_DISPATCHED.equals(task.getStatus())
                    && elapsed > DISPATCH_TIMEOUT_MS)
                    || (TaskDoc.STATUS_RUNNING.equals(task.getStatus())
                    && elapsed > RUNNING_TIMEOUT_MS);

            if (timeout) {
                task.setStatus(TaskDoc.STATUS_FAILED);
                task.setFinishTime(now);
                task.setRemark("回执超时（等待 " + (elapsed / 1000) + " 秒无响应）");
                taskRepository.save(task);
                log.warn("[任务] {} 回执超时，判定失败：等待 {} 秒",
                        task.getTaskId(), elapsed / 1000);
            }
        }
    }

    // ==================== 查询 ====================

    /** 任务详情：台账 + 回执流水时间轴（对应 UC-13） */
    public Map<String, Object> detail(String taskId) {
        TaskDoc task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));

        // 回执流水在时序集合 task_log 里，按时间正序排（前端直接渲染成时间轴）
        Query q = Query.query(Criteria.where("taskId").is(taskId))
                .with(Sort.by(Sort.Direction.ASC, "eventTime"));
        List<TaskLogDoc> acks = mongoTemplate.find(q, TaskLogDoc.class);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("task", task);
        resp.put("acks", acks);
        return resp;
    }

    /**
     * 多条件分页查询（列表页）。
     * 与 AlarmService.queryAlarms() 用同一套 MongoTemplate + Criteria 模式。
     */
    public Map<String, Object> query(String status, String deviceNo, String taskType,
                                     Long startTime, Long endTime, int page, int size) {
        List<Criteria> conditions = new ArrayList<>();

        if (status != null && !status.isBlank() && !"ALL".equals(status)) {
            conditions.add(Criteria.where("status").is(status));
        }
        if (deviceNo != null && !deviceNo.isBlank()) {
            conditions.add(Criteria.where("deviceNo").is(deviceNo));
        }
        if (taskType != null && !taskType.isBlank()) {
            conditions.add(Criteria.where("taskType").is(taskType));
        }
        if (startTime != null) {
            conditions.add(Criteria.where("createTime").gte(startTime));
        }
        if (endTime != null) {
            conditions.add(Criteria.where("createTime").lte(endTime));
        }

        Query query = new Query();
        if (!conditions.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(conditions.toArray(new Criteria[0])));
        }

        long total = mongoTemplate.count(query, TaskDoc.class);

        query.with(Sort.by(Sort.Direction.DESC, "createTime"))
                .skip((long) Math.max(page - 1, 0) * size)
                .limit(size);

        List<TaskDoc> items = mongoTemplate.find(query, TaskDoc.class);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("items", items);
        return resp;
    }

    /** 可选设备（在线且未停用）—— 把业务规则收敛在后端一处，前端不重复实现 */
    public List<DeviceStatus> availableDevices(String deviceType) {
        return deviceStatusRepository.findAll().stream()
                .filter(d -> "ONLINE".equals(d.getStatus()))
                .filter(d -> !Boolean.FALSE.equals(d.getEnabled()))
                .filter(d -> deviceType == null || deviceType.isBlank()
                        || deviceType.equals(d.getDeviceType()))
                .toList();
    }

    // ==================== 内部工具 ====================

    /** 挑一台在线且启用的指定类型设备（找不到返回 null） */
    private DeviceStatus pickOnlineDevice(String deviceType) {
        return availableDevices(deviceType).stream().findFirst().orElse(null);
    }
}
