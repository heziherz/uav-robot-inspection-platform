package com.uav.platformservice.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 JWT 实现（HS256 签名）。
 *
 * 结构：base64Url(header) . base64Url(payload) . base64Url(HMAC-SHA256 签名)
 *
 * 为什么自己实现：jjwt 依赖 Jackson 2，会加剧项目（Jackson 3）的版本混乱；
 *               JWT 本身结构简单，自实现也便于讲清其原理。
 */
public final class JwtUtil {

    /** 签名密钥（演示用；生产应放入配置/环境变量并定期轮换） */
    private static final String SECRET = "uav-robot-inspection-platform-secret-2026";
    /** 有效期：8 小时 */
    private static final long EXPIRE_MS = 8 * 3600 * 1000L;

    private JwtUtil() {
    }

    /** 生成 token */
    public static String create(String username, String role) {
        String header = b64Encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long exp = System.currentTimeMillis() + EXPIRE_MS;
        String payload = b64Encode("{\"sub\":\"" + username + "\",\"role\":\"" + role + "\",\"exp\":" + exp + "}");
        String signature = sign(header + "." + payload);
        return header + "." + payload + "." + signature;
    }

    /** 校验签名与有效期；合法返回 claims（username/role），否则返回 null */
    public static Map<String, Object> parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            // ① 校验签名（防止篡改 role 等信息）
            if (!sign(parts[0] + "." + parts[1]).equals(parts[2])) {
                return null;
            }
            // ② 解析 payload
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String username = extract(json, "sub");
            String role = extract(json, "role");
            String exp = extract(json, "exp");
            if (username == null || role == null || exp == null) {
                return null;
            }
            // ③ 校验是否过期
            if (System.currentTimeMillis() > Long.parseLong(exp)) {
                return null;
            }

            Map<String, Object> claims = new HashMap<>();
            claims.put("username", username);
            claims.put("role", role);
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 内部工具 ----------

    /** HMAC-SHA256 签名 */
    private static String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private static String b64Encode(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 从简单 JSON 中取值（避免为工具类引入 JSON 依赖） */
    private static String extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^,\"}]+)\"?").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
