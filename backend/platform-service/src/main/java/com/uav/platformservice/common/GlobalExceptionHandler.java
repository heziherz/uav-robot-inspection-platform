package com.uav.platformservice.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器 —— 把业务异常统一映射为合适的 HTTP 状态码。
 *
 * 【为什么需要全局处理器】
 *   项目原先只有一个局部处理器（写在 TaskController 里），但 {@code @ExceptionHandler}
 *   是**控制器级**的，只对声明它的那个 Controller 生效。结果是：
 *
 *     · TaskService 的校验失败  → 400（因为 TaskController 有处理器）✅
 *     · AuthService 的登录失败  → 500 ❌  ← 系统测试 IT-02 发现的
 *     · UserService 的 6 处校验 → 500 ❌
 *     · MediaService 的 3 处校验 → 500 ❌
 *
 *   改为全局处理器后，一次覆盖所有 Controller —— 这也符合项目一贯的
 *   "横切关注点集中处理"原则（同 AuthInterceptor）。
 *
 * 【只处理两类，其余放行】
 *   业务异常与认证异常由本类接管；**其余异常不做拦截**，
 *   交给 Spring 默认处理（返回 500 并记录堆栈）——
 *   这样"消息发送失败""JWT 签名失败"等真实的服务端故障不会被误报成客户端错误。
 *
 * 【响应格式】与项目其它错误响应保持一致：{ "status": 400, "message": "..." }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 认证失败（用户名密码错误、账号已停用）→ 401 */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthException e) {
        log.warn("[认证失败] {}", e.getMessage());
        return build(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /** 业务校验失败（参数非法、状态不允许等）→ 400 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("[业务校验失败] {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 资源不存在 → 404 */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        log.warn("[资源不存在] {}", e.getMessage());
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("message", (message == null || message.isBlank()) ? "请求处理失败" : message);
        return ResponseEntity.status(status).body(body);
    }
}
