package com.uav.platformservice.service;

/**
 * @Description:
 * @Author: hezi
 * @Date: 2026/9/11
 */
import com.uav.platformservice.common.BusinessException;
import com.uav.platformservice.common.NotFoundException;
import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.model.HeartbeatMessage;
import com.uav.platformservice.repository.DeviceStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备业务逻辑（读写共用）。覆盖同一业务领域的两类规则：
 *   ① 在线状态维护：心跳续期（被动触发）+ 超时离线判定（定时触发）
 *   ② 设备台账维护：新增 / 编辑 / 停用（管理员操作，UC-21）
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** 心跳超时阈值：15 秒（心跳间隔 5s × 3 个周期） */
    private static final long TIMEOUT_MS = 15_000L;

    private final DeviceStatusRepository deviceStatusRepository;

    public DeviceService(DeviceStatusRepository deviceStatusRepository) {
        this.deviceStatusRepository = deviceStatusRepository;
    }

    // ==================== ① 在线状态维护 ====================

    /** 被动触发：收到心跳 → 更新状态、续期"最后心跳时间"；首次出现即完成设备注册 */
    public void handleHeartbeat(HeartbeatMessage hb) {
        long now = System.currentTimeMillis();
        DeviceStatus st = deviceStatusRepository.findById(hb.deviceNo()).orElse(null);

        if (st == null) {
            // 首次出现 → 等同于"设备注册接入"（UC-01）
            st = new DeviceStatus();
            st.setDeviceNo(hb.deviceNo());
            st.setDeviceType(hb.deviceType());
            st.setFirstSeenTime(now);
            st.setHeartbeatCount(1L);
            st.setEnabled(true);                   // 自动接入的设备默认启用
            log.info("[设备注册] 新设备接入: {} ({})", hb.deviceNo(), hb.deviceType());
        } else {
            st.setHeartbeatCount(st.getHeartbeatCount() == null ? 1L : st.getHeartbeatCount() + 1);
        }

        // 记录本次上线时刻（前端据此显示"在线时长"）。
        // 离线时该字段会被置空，所以"为空"就代表当前没有在计时 —— 首次上线、离线恢复都会走到这里。
        if (st.getOnlineSinceTime() == null) {
            st.setOnlineSinceTime(now);
            log.info("[设备上线] {} 开始计时", hb.deviceNo());
        }

        // 只更新"运行时状态"字段 —— 台账字段（名称/型号/区域）由管理员维护，心跳不覆盖
        st.setBattery(hb.battery());
        st.setStatus(hb.status());
        st.setEventTime(hb.eventTime());
        st.setLastHeartbeatTime(now);

        deviceStatusRepository.save(st);
    }

    /** 主动触发：定时扫描 → 心跳超时判定离线 / 恢复判定在线（UC-05 / TC-010） */
    @Scheduled(fixedRate = 5000)
    public void detectOfflineDevices() {
        long now = System.currentTimeMillis();

        for (DeviceStatus st : deviceStatusRepository.findAll()) {
            if (st.getLastHeartbeatTime() == null) {
                continue;
            }
            boolean timedOut = (now - st.getLastHeartbeatTime()) > TIMEOUT_MS;
            String current = st.getStatus();

            if (timedOut && !"OFFLINE".equals(current)) {
                st.setStatus("OFFLINE");
                st.setOnlineSinceTime(null);       // 停止在线计时
                deviceStatusRepository.save(st);
                log.warn("[离线检测] {} 心跳超时，状态变更为 OFFLINE", st.getDeviceNo());

            } else if (!timedOut && "OFFLINE".equals(current)) {
                st.setStatus("ONLINE");
                deviceStatusRepository.save(st);
                log.info("[离线检测] {} 心跳恢复，状态变更为 ONLINE", st.getDeviceNo());
            }
        }
    }

    // ==================== ② 设备台账维护（UC-21） ====================

    /** 设备列表 */
    public List<DeviceStatus> listAll() {
        return deviceStatusRepository.findAll();
    }

    /** 单台设备 */
    /**
     * 查询单台设备。
     * 不存在时抛 NotFoundException（→404），而不是返回 null ——
     * 返回 null 会让接口变成"200 + 空响应体"，调用方无法区分"没有这个设备"和"请求成功但没数据"。
     */
    public DeviceStatus find(String deviceNo) {
        return deviceStatusRepository.findById(deviceNo)
                .orElseThrow(() -> new NotFoundException("设备不存在: " + deviceNo));
    }

    /**
     * 新增设备台账：编号由系统**自动生成**，不接受人工指定。
     *
     * 原因：设备编号会作为 Kafka 消息的 key，并与设备端上报的 deviceNo 强绑定；
     *      人工随意填写会导致平台台账与真实设备对不上（如凭空造出一个永远不上线的编号）。
     */
    public DeviceStatus createDevice(DeviceStatus device) {
        if (device.getDeviceType() == null || device.getDeviceType().isBlank()) {
            throw new BusinessException("设备类型不能为空");
        }

        device.setDeviceNo(generateDeviceNo(device.getDeviceType()));
        device.setStatus("OFFLINE");        // 新登记设备默认离线，等心跳上报后转在线
        device.setEnabled(true);
        device.setFirstSeenTime(System.currentTimeMillis());
        device.setHeartbeatCount(0L);

        DeviceStatus saved = deviceStatusRepository.save(device);
        log.info("[设备台账] 新增设备: {} ({})", saved.getDeviceNo(), saved.getDeviceName());
        return saved;
    }

    /** 按类型生成下一个可用编号：UAV-001 / DOG-001 …（取现有最大序号 +1） */
    private String generateDeviceNo(String deviceType) {
        String prefix = "DRONE".equals(deviceType) ? "UAV-" : "DOG-";
        int maxSeq = deviceStatusRepository.findAll().stream()
                .map(DeviceStatus::getDeviceNo)
                .filter(no -> no != null && no.startsWith(prefix))
                .map(no -> no.substring(prefix.length()))
                .filter(seq -> seq.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + String.format("%03d", maxSeq + 1);
    }

    /** 编辑设备台账（只允许修改档案字段，deviceNo 不可改） */
    public DeviceStatus updateDevice(String deviceNo, DeviceStatus patch) {
        DeviceStatus st = deviceStatusRepository.findById(deviceNo)
                .orElseThrow(() -> new BusinessException("设备不存在: " + deviceNo));

        if (patch.getDeviceName() != null) st.setDeviceName(patch.getDeviceName());
        if (patch.getModel() != null)      st.setModel(patch.getModel());
        if (patch.getArea() != null)       st.setArea(patch.getArea());
        if (patch.getDeviceType() != null) st.setDeviceType(patch.getDeviceType());
        if (patch.getEnabled() != null)    st.setEnabled(patch.getEnabled());

        DeviceStatus saved = deviceStatusRepository.save(st);
        log.info("[设备台账] 更新设备: {}", deviceNo);
        return saved;
    }

    /**
     * 停用设备（逻辑删除）。
     * 采用逻辑删除而非物理删除：设备留有历史轨迹、告警等数据，物理删除会产生孤儿数据。
     */
    public DeviceStatus disableDevice(String deviceNo) {
        DeviceStatus st = deviceStatusRepository.findById(deviceNo)
                .orElseThrow(() -> new BusinessException("设备不存在: " + deviceNo));

        st.setEnabled(false);
        DeviceStatus saved = deviceStatusRepository.save(st);
        log.info("[设备台账] 停用设备: {}", deviceNo);
        return saved;
    }

    /** 重新启用设备（停用是可逆的） */
    public DeviceStatus enableDevice(String deviceNo) {
        DeviceStatus st = deviceStatusRepository.findById(deviceNo)
                .orElseThrow(() -> new BusinessException("设备不存在: " + deviceNo));

        st.setEnabled(true);
        DeviceStatus saved = deviceStatusRepository.save(st);
        log.info("[设备台账] 启用设备: {}", deviceNo);
        return saved;
    }

    /**
     * 删除设备台账（物理删除）。
     *
     * 说明：只删除台账记录，**历史轨迹 / 告警 / 影像数据保留**，避免产生孤儿数据、保留追溯能力。
     * 注意：若该设备仍在线上报心跳，删除后会被"首次心跳自动注册"重新建回台账。
     */
    public void deleteDevice(String deviceNo) {
        DeviceStatus st = deviceStatusRepository.findById(deviceNo)
                .orElseThrow(() -> new BusinessException("设备不存在: " + deviceNo));

        deviceStatusRepository.delete(st);
        log.warn("[设备台账] 删除设备: {}（历史轨迹/告警数据保留）", deviceNo);
    }
}
