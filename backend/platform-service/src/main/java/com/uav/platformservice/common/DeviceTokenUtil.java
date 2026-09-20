package com.uav.platformservice.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 设备令牌工具（HMAC-SHA256）。
 *
 * 【这个令牌没有"有效期"，只有"时间偏差容忍窗口"】
 *   设备的每次请求都【现算】一个令牌：token = HMAC(deviceNo + ":" + 当前时间戳, secret)。
 *   时间戳是"本次请求的时间"，不是"令牌签发的时间"，因此：
 *     · 设备在线多久都无所谓 —— 不存在"令牌过期要续期"
 *     · 平台不需要存储令牌 —— 无状态，用同一算法再算一遍比对即可
 *
 *   窗口（默认 5 分钟）只用来覆盖两件事：
 *     ① 网络延迟    ② 设备与服务器的时钟偏差
 *   窗口同时也是【重放窗口】——截获的令牌最多只能在窗口内被重放，故不宜设得过大。
 *
 * 【为什么这样就够用】
 *   本项目中仿真端与平台同机部署，时钟完全一致，窗口额度几乎全部留给网络延迟（内网毫秒级）。
 *   若将来设备部署到异地，应放宽窗口或由平台返回服务器时间供设备校正。
 *
 * 【与"设备长期在线"场景的正解对照】
 *   真正的长期在线设备，业界用 mTLS 设备证书（有效期 1~10 年，靠 CRL/OCSP 吊销），
 *   而不是靠拉长令牌有效期。本项目用 HMAC 签名，属于轻量级方案。
 *
 * 注意：平台侧（本类）与设备侧各有一份实现，**算法必须完全一致**。
 */
public final class DeviceTokenUtil {

    private static final String ALGORITHM = "HmacSHA256";
    /** 取 HMAC 结果的前 16 字节（128 位）作为令牌 */
    private static final int TOKEN_BYTES = 16;

    private DeviceTokenUtil() {
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

    /**
     * 校验令牌。
     *
     * @param windowSeconds 时间偏差容忍窗口（本项目为 300 秒 = 5 分钟）
     * @return 令牌有效且未超出时间窗 → true
     */
    public static boolean verify(String deviceNo, long timestampSec,
                                 String token, String secret, long windowSeconds) {
        if (token == null || token.isBlank()) {
            return false;
        }

        // ① 时间窗校验：防止截获后长期重放
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - timestampSec) > windowSeconds) {
            return false;
        }

        // ② 签名校验：恒定时间比较，避免通过响应耗时逐字节猜解令牌（时序侧信道）
        String expected = issue(deviceNo, timestampSec, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
