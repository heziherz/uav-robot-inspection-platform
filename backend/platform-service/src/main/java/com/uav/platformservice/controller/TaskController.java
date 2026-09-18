package com.uav.platformservice.controller;

import com.uav.platformservice.model.DeviceStatus;
import com.uav.platformservice.model.TaskDoc;
import com.uav.platformservice.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 巡检任务接口（入站适配器）。
 *
 * 对应需求：
 *   UC-12 创建并下发巡检任务（A1 巡检值班员）
 *   UC-13 查看任务执行过程与历史（A1 巡检值班员）
 *
 * 【权限说明 —— 刻意不加 ADMIN 限制】
 *   UC-12 的参与者是"巡检值班员"，不是管理员。
 *   AuthInterceptor 默认对所有已登录用户放行（只有 /api/devices 写操作和 /api/users 限 ADMIN），
 *   所以本接口天然对值班员开放 —— 这正是需求要的，不要"顺手"改成管理员专属。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建任务。
     * autoDispatch=true（默认）时创建后立即下发，一步完成；false 则只建草稿。
     */
    @PostMapping
    public TaskDoc create(@RequestBody TaskDoc req,
                          @RequestParam(defaultValue = "true") boolean autoDispatch,
                          HttpServletRequest httpReq) {
        String currentUser = (String) httpReq.getAttribute("currentUser");
        return taskService.createTask(req, currentUser, autoDispatch);
    }

    /** 任务列表（多条件 + 服务端分页） */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String deviceNo,
                                    @RequestParam(required = false) String taskType,
                                    @RequestParam(required = false) Long startTime,
                                    @RequestParam(required = false) Long endTime,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return taskService.query(status, deviceNo, taskType, startTime, endTime, page, size);
    }

    /** 可选设备：只返回在线且未停用的（业务规则收敛在后端一处） */
    @GetMapping("/available-devices")
    public List<DeviceStatus> availableDevices(@RequestParam(required = false) String deviceType) {
        return taskService.availableDevices(deviceType);
    }

    /** 任务详情：台账 + 回执流水时间轴（UC-13） */
    @GetMapping("/{taskId}")
    public Map<String, Object> detail(@PathVariable String taskId) {
        return taskService.detail(taskId);
    }

    /** 下发（也用于失败后重发 —— 需求 UC-12 备选流"可重发"） */
    @PostMapping("/{taskId}/dispatch")
    public TaskDoc dispatch(@PathVariable String taskId) {
        return taskService.dispatch(taskId);
    }

    /** 取消任务 */
    @PostMapping("/{taskId}/cancel")
    public TaskDoc cancel(@PathVariable String taskId,
                          @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return taskService.cancel(taskId, reason);
    }

    /**
     * 业务校验失败（设备离线、任务状态不允许等）统一转 400 + 可读消息，
     * 让前端能直接把 message 弹给值班员看。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", 400, "message", e.getMessage()));
    }
}
