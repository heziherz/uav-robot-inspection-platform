package com.uav.platformservice.model.dto;


/** 告警处置请求体 */
public record AlarmHandleRequest(
        String action,      // CLOSE（确认关闭）/ REVIEW（指派复核）
        String handleBy,    // 处置人（登录功能未做前由前端传模拟值）
        String remark       // 处置备注
) {
}
