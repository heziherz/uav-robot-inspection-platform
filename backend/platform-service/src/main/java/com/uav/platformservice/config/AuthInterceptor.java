package com.uav.platformservice.config;

import com.uav.platformservice.common.DeviceTokenUtil;
import com.uav.platformservice.common.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 认证与鉴权拦截器。
 *
 * 【角色】基础设施层（横切关注点）—— 与业务无关，统一拦截所有 /api/** 请求。
 * 【数据流】按请求来源分两条通道：
 *     ① 人机通道（Web 前端）：读 Authorization 头 → 校验 JWT → 校验角色权限
 *     ② 设备通道（仿真设备）：读 X-Device-* 头 → 校验 HMAC 设备令牌
 *
 * 之所以用拦截器而不是在每个 Controller 里写校验：认证是**横切关注点**，
 * 分散写会重复且容易漏，集中在拦截器里"一处生效、全局覆盖"。
 * **设备通道同样遵循这一原则**——不因为它只有一两个接口就另起一套校验逻辑。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 设备令牌校验用的预共享密钥（两端一致） */
    @Value("${device.token.secret}")
    private String deviceSecret;

    /** 时间偏差容忍窗口（本项目 300 秒 = 5 分钟） */
    @Value("${device.token.window-seconds:300}")
    private long deviceTokenWindow;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // ★ 设备通道：影像上传授权接口走【设备令牌】认证，不走 JWT
        //   原因：设备是软件程序，没有"登录"概念，也不该持有用户的 JWT。
        if (request.getRequestURI().startsWith("/api/media/upload-auth")) {
            return verifyDevice(request, response);
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(response, 401, "未登录，请先登录");
        }

        Map<String, Object> claims = JwtUtil.parse(auth.substring(7));
        if (claims == null) {
            return reject(response, 401, "登录已过期，请重新登录");
        }

        String role = (String) claims.get("role");
        if (!hasPermission(request, role)) {
            log.warn("[鉴权] {} 无权访问 {} {}", claims.get("username"),
                    request.getMethod(), request.getRequestURI());
            return reject(response, 403, "当前角色无权执行该操作");
        }

        // 把当前用户信息挂到 request 上，供 Controller 使用
        request.setAttribute("currentUser", claims.get("username"));
        request.setAttribute("currentRole", role);
        return true;
    }

    /**
     * 设备通道鉴权：校验 HMAC 设备令牌。
     *
     * 请求头约定：
     *   X-Device-No     设备编号
     *   X-Device-Ts     时间戳（秒）
     *   X-Device-Token  HMAC-SHA256(deviceNo + ":" + ts, secret) 的前 16 字节
     *
     * 校验三件事：
     *   ① 三个头是否齐全
     *   ② 时间戳是否在容忍窗口内（默认 5 分钟）——压缩重放窗口
     *   ③ 令牌签名是否匹配（恒定时间比较）——防止伪造与冒用
     */
    private boolean verifyDevice(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String deviceNo = request.getHeader("X-Device-No");
        String tsRaw    = request.getHeader("X-Device-Ts");
        String token    = request.getHeader("X-Device-Token");

        if (deviceNo == null || tsRaw == null || token == null) {
            return reject(response, 401, "缺少设备身份信息");
        }

        long ts;
        try {
            ts = Long.parseLong(tsRaw);
        } catch (NumberFormatException e) {
            return reject(response, 401, "设备时间戳格式错误");
        }

        if (!DeviceTokenUtil.verify(deviceNo, ts, token, deviceSecret, deviceTokenWindow)) {
            log.warn("[设备鉴权] 令牌无效或已过期: deviceNo={}", deviceNo);
            return reject(response, 401, "设备令牌无效或已过期");
        }

        // 把设备身份挂到 request 上，供 Controller 使用
        request.setAttribute("currentDevice", deviceNo);
        return true;
    }

    /**
     * 角色权限规则：
     *   ADMIN  —— 设备增删改、用户管理
     *   其他角色 —— 查询类接口均可访问（值班员/运维需要的态势、告警、检索等）
     */
    private boolean hasPermission(HttpServletRequest req, String role) {
        String path = req.getRequestURI();
        boolean isWrite = !"GET".equalsIgnoreCase(req.getMethod());

        if (path.startsWith("/api/devices") && isWrite) {
            return "ADMIN".equals(role);
        }
        if (path.startsWith("/api/users")) {
            return "ADMIN".equals(role);
        }
        return true;
    }

    /** 统一返回 JSON 错误体 */
    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
        return false;
    }
}
