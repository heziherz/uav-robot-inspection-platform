package com.uav.platformservice.controller;

import com.uav.platformservice.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 统计分析接口（UC-17）。
 * 所有登录用户均可访问（值班员 / 管理员 / 运维都需要看统计）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 总览 KPI（设备数 / 在线数 / 告警数 / 待处理数 / 影像数） */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return statsService.overview();
    }

    /** 告警类型分布（ES 聚合） */
    @GetMapping("/alarms/types")
    public List<Map<String, Object>> alarmTypes() {
        return statsService.alarmTypeDistribution();
    }

    /** 告警级别分布（ES 聚合） */
    @GetMapping("/alarms/levels")
    public List<Map<String, Object>> alarmLevels() {
        return statsService.alarmLevelDistribution();
    }

    /** 告警趋势：按小时（ES 聚合） */
    @GetMapping("/alarms/trend")
    public List<Map<String, Object>> alarmTrend() {
        return statsService.alarmTrend();
    }
}
