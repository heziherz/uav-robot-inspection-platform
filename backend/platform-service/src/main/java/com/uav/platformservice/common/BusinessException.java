package com.uav.platformservice.common;

/**
 * 业务校验失败异常 —— 表示「客户端的请求本身有问题」，统一映射为 HTTP 400。
 *
 * 【为什么需要这个类】
 *   项目原先用 {@link IllegalStateException} 和 {@link IllegalArgumentException} 表达业务校验失败，
 *   但这两个是 JDK 的通用异常，**同时被用来表达服务端内部错误**：
 *
 *     · 业务校验失败：设备离线、任务状态不允许、用户名已存在   → 应返回 400
 *     · 服务端内部错误：消息发送失败、JWT 签名失败、令牌计算失败 → 应返回 500
 *
 *   两类问题混用同一异常类型，导致无法在全局异常处理器里区分，
 *   若一刀切映射为 400，会把"Kafka 挂了"这类**服务端故障**误报成"客户端请求有误"，
 *   排查时会被严重误导。
 *
 *   因此引入本类明确区分：**业务异常 → 400；其余异常不拦截，保持 500**。
 *
 * 【使用场景】参数校验、状态机校验、资源存在性校验等**可归因于客户端**的失败。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
