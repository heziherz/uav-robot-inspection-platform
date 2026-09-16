package com.uav.platformservice.model.dto;

import java.util.List;

/** 告警批量处置请求体 */
public record AlarmBatchHandleRequest(
        List<String> alarmIds,      // 待处置的告警编号列表
        String action,              // CLOSE（确认关闭）/ REVIEW（指派复核）
        String handleBy,            // 处置人
        String remark               // 处置备注
) {
}
