package com.uav.platformservice.controller;

import com.uav.platformservice.model.User;
import com.uav.platformservice.model.dto.UserCreateRequest;
import com.uav.platformservice.model.dto.UserPasswordRequest;
import com.uav.platformservice.model.dto.UserUpdateRequest;
import com.uav.platformservice.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理接口（UC-22）。
 * 权限：/api/users/** 在 AuthInterceptor 中限制为 ADMIN 角色。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 用户列表 */
    @GetMapping
    public List<User> list() {
        return userService.listAll();
    }

    /** 新增用户 */
    @PostMapping
    public User create(@RequestBody UserCreateRequest req) {
        return userService.createUser(req.username(), req.password(), req.realName(), req.role());
    }

    /** 编辑用户（姓名 / 角色 / 启用状态） */
    @PutMapping("/{username}")
    public User update(@PathVariable String username, @RequestBody UserUpdateRequest req) {
        return userService.updateUser(username, req.realName(), req.role(), req.enabled());
    }

    /** 重置密码 */
    @PutMapping("/{username}/password")
    public Map<String, Object> resetPassword(@PathVariable String username,
                                             @RequestBody UserPasswordRequest req) {
        userService.resetPassword(username, req.password());
        return Map.of("reset", username);
    }

    /** 删除用户 */
    @DeleteMapping("/{username}")
    public Map<String, Object> delete(@PathVariable String username) {
        userService.deleteUser(username);
        return Map.of("deleted", username);
    }
}
