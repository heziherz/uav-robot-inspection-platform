package com.uav.platformservice.controller;

import com.uav.platformservice.model.AlarmDoc;
import com.uav.platformservice.service.AlarmService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 告警接口（读路径）—— 供前端告警列表页调用 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    /**
     * 告警列表（服务端分页）。
     *
     * @param status 处置状态筛选（PENDING / REVIEWING / HANDLED；不传或 ALL 表示全部）
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     */
    @GetMapping
    public java.util.Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return alarmService.pageAlarms(status, page, size);
    }

    /** 批量处置告警（勾选多条一起处置） */
    @PutMapping("/handle-batch")
    public java.util.Map<String, Object> handleBatch(
            @RequestBody com.uav.platformservice.model.dto.AlarmBatchHandleRequest req) {
        int handled = alarmService.handleBatch(req.alarmIds(), req.action(), req.handleBy(), req.remark());
        return java.util.Map.of("handled", handled);
    }
    /** 处置告警：确认关闭 / 指派机器狗抵近复核（UC-15） */
    @PutMapping("/{alarmId}/handle")
    public AlarmDoc handle(@PathVariable String alarmId,
                           @RequestBody com.uav.platformservice.model.dto.AlarmHandleRequest req) {
        return alarmService.handleAlarmAction(alarmId, req.action(), req.handleBy(), req.remark());
    }

    /** 走 ES 的检索接口（按类型 / 级别过滤） */
    @GetMapping("/search")
    public List<java.util.Map<String, Object>> search(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String level) {
        return alarmService.searchInEs(type, level);
    }
}