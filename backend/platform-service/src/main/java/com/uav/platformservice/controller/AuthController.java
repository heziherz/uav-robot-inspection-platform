package com.uav.platformservice.controller;

import com.uav.platformservice.model.dto.LoginRequest;
import com.uav.platformservice.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口。
 * 注意：/api/auth/login 已在拦截器中放行（登录本身不需要 token）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 用户登录 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        return authService.login(req.username(), req.password());
    }
}
