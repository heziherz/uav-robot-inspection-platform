package com.uav.platformservice.common;

/**
 * 资源不存在异常 —— 统一映射为 HTTP 404。
 *
 * 【为什么不用 BusinessException（400）】
 *   HTTP 语义上两者不同：
 *     · 400 Bad Request —— 请求本身有问题（参数非法、状态不允许）
 *     · 404 Not Found   —— 请求合法，但目标资源不存在
 *   路径中的资源编号写错了属于后者。前端据此可以区分处理：
 *     404 → 提示"该资源不存在"（可能是数据被删了）
 *     400 → 提示具体校验原因
 *
 * 【背景】系统测试发现：GET /api/devices/{deviceNo} 查询不存在的设备时，
 *   原先返回 200 + 空响应体 —— 调用方会误以为"请求成功，只是没数据"。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
