package com.uav.platformservice.model.dto;

/** 编辑用户请求体（username 不可改） */
public record UserUpdateRequest(
        String realName,
        String role,
        Boolean enabled
) {
}
