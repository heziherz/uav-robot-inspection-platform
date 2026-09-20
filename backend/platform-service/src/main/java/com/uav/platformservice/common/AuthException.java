package com.uav.platformservice.common;

/**
 * 认证失败异常 —— 统一映射为 HTTP 401。
 *
 * 【为什么单独一个类型而不是并入 BusinessException（400）】
 *   语义不同：
 *     · 400 Bad Request   —— 请求格式/参数有问题
 *     · 401 Unauthorized  —— 身份未通过验证（凭据错误、账号停用）
 *   "用户名或密码错误"属于后者。前端也据此区分处理：
 *     401 → 跳登录页；400 → 就地提示错误信息。
 *
 * 【背景】这是系统测试 IT-02 发现的缺陷：
 *   登录失败原先抛出 IllegalArgumentException 且无人接住，最终返回 **500**，
 *   前端无法区分"密码错了"与"服务器崩了"。
 */
public class AuthException extends RuntimeException {

    public AuthException(String message) {
        super(message);
    }
}
