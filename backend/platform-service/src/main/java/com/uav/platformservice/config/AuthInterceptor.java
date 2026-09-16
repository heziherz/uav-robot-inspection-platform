package com.uav.platformservice.config;

import com.uav.platformservice.common.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 认证与鉴权拦截器。
 *
 * 【角色】基础设施层（横切关注点）—— 与业务无关，统一拦截所有 /api/** 请求。
 * 【数据流】读 Authorization 头 → 校验 JWT → 校验角色权限 → 放行 / 返回 401 或 403
 *
 * 之所以用拦截器而不是在每个 Controller 里写校验：认证是**横切关注点**，
 * 分散写会重复且容易漏，集中在拦截器里"一处生效、全局覆盖"。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
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
