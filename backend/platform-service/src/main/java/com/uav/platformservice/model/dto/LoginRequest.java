package com.uav.platformservice.model.dto;

/** 登录请求体 */
public record LoginRequest(
        String username,
        String password
) {
}
