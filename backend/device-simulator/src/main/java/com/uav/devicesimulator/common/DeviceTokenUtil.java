package com.uav.devicesimulator.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 设备令牌工具（HMAC-SHA256）—— 设备侧。
 *
 * 【与平台侧 com.uav.platformservice.common.DeviceTokenUtil 是同一套算法】
 *   两端必须完全一致：设备算什么，平台就验什么。
 *
 * 【核心概念：没有"有效期"，只有"时间偏差容忍窗口"】
 *   每次请求都【现算】令牌，时间戳取的是**本次请求的时间**，不是签发时间。
 *   因此设备在线多久都无所谓——不存在"令牌过期要续期"这回事。
 *
 *   平台侧的窗口（本项目 5 分钟）只用来覆盖网络延迟与时钟偏差，
 *   同时也是重放窗口：截获的令牌最多只能在 5 分钟内被重放。
 */
public final class DeviceTokenUtil {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int TOKEN_BYTES = 16;

    private DeviceTokenUtil() {
    }

    /** 当前时间戳（秒），配合 issue() 使用 */
    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * 计算令牌：HMAC-SHA256(deviceNo + ":" + timestampSec, secret) 的前 16 字节（十六进制）。
     */
    public static String issue(String deviceNo, long timestampSec, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(
                    (deviceNo + ":" + timestampSec).getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
            for (int i = 0; i < TOKEN_BYTES; i++) {
                sb.append(String.format("%02x", raw[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("设备令牌计算失败", e);
        }
    }
}
