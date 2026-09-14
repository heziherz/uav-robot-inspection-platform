package com.uav.platformservice.controller;

import com.uav.platformservice.model.TelemetryDoc;
import com.uav.platformservice.service.TelemetryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 位置接口（读路径）—— 供前端地图打点 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /** 每台设备的最新位置 */
    @GetMapping("/latest")
    public List<TelemetryDoc> latest() {
        return telemetryService.latestPositions();
    }
}