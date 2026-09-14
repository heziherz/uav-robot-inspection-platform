package com.uav.platformservice.service;

/**
 * @Description:
 * @Author: hezi
 * @Date: 2026/9/11
 */
import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.model.HeartbeatMessage;
import com.uav.platformservice.repository.DeviceStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 设备业务逻辑（读写共用）。
 * 5.1 阶段只实现一件事：处理心跳 —— 更新设备状态，首次出现即“设备注册”。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceStatusRepository deviceStatusRepository;

    public DeviceService(DeviceStatusRepository deviceStatusRepository) {
        this.deviceStatusRepository = deviceStatusRepository;
    }

    /** 处理一条心跳 */
    public void handleHeartbeat(HeartbeatMessage hb) {
        long now = System.currentTimeMillis();

        DeviceStatus st = deviceStatusRepository.findById(hb.deviceNo()).orElse(null);

        if (st == null) {
            // 首次出现 → 等同于“设备注册接入”（UC-01 的效果在这里实现）
            st = new DeviceStatus();
            st.setDeviceNo(hb.deviceNo());
            st.setDeviceType(hb.deviceType());
            st.setFirstSeenTime(now);
            st.setHeartbeatCount(1L);
            log.info("[设备注册] 新设备接入: {} ({})", hb.deviceNo(), hb.deviceType());
        } else {
            st.setHeartbeatCount(st.getHeartbeatCount() == null ? 1L : st.getHeartbeatCount() + 1);
        }

        st.setBattery(hb.battery());
        st.setStatus(hb.status());
        st.setEventTime(hb.eventTime());
        st.setLastHeartbeatTime(now);

        deviceStatusRepository.save(st);   // upsert：同 deviceNo 覆盖更新
    }

    /** 查所有设备（前端设备列表用） */
    public java.util.List<com.uav.platformservice.model.DeviceStatus> listAll() {
        return deviceStatusRepository.findAll();
    }

    /** 查单台设备 */
    public com.uav.platformservice.model.DeviceStatus find(String deviceNo) {
        return deviceStatusRepository.findById(deviceNo).orElse(null);
    }
}
