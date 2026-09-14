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
     * 告警列表。
     * 不传 status：返回最近 50 条；传 status=PENDING：只返回待处理。
     */
    @GetMapping
    public List<AlarmDoc> list(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return alarmService.listAll();
        }
        return alarmService.listByStatus(status);
    }
    /** 走 ES 的检索接口（按类型 / 级别过滤） */
    @GetMapping("/search")
    public List<java.util.Map<String, Object>> search(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String level) {
        return alarmService.searchInEs(type, level);
    }
}