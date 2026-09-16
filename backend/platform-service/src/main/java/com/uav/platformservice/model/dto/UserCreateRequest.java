package com.uav.platformservice.model.dto;

/** 新增用户请求体 */
public record UserCreateRequest(
        String username,
        String password,        // 明文密码，服务端 BCrypt 加密后存储
        String realName,
        String role             // ADMIN / OPERATOR / OPS
) {
}
