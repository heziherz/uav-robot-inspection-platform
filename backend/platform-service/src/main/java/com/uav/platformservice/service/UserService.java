package com.uav.platformservice.service;

import com.uav.platformservice.model.User;
import com.uav.platformservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户与权限业务逻辑（UC-22）。
 * 【角色】Service 层
 * 【联系】MongoDB（UserRepository）+ BCrypt（密码加密）
 * 【数据流】Controller → 校验/加密 → Repository → MongoDB
 *
 * 权限说明：本服务的接口在 AuthInterceptor 中受保护，仅 ADMIN 可访问。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 用户列表（**清除密码哈希**后再下发，避免敏感信息泄露） */
    public List<User> listAll() {
        List<User> users = userRepository.findAll();
        users.forEach(u -> u.setPasswordHash(null));
        return users;
    }

    /** 新增用户 */
    public User createUser(String username, String rawPassword, String realName, String role) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }

        User u = new User();
        u.setUserId(username);                              // 用 username 作主键
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRealName(realName);
        u.setRole(role == null || role.isBlank() ? "OPERATOR" : role);
        u.setEnabled(true);
        u.setCreateTime(System.currentTimeMillis());

        User saved = userRepository.save(u);
        saved.setPasswordHash(null);
        log.info("[用户管理] 新增用户: {} ({})", username, saved.getRole());
        return saved;
    }

    /** 编辑用户（姓名 / 角色 / 启用状态） */
    public User updateUser(String username, String realName, String role, Boolean enabled) {
        User u = userRepository.findById(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));

        if (realName != null) u.setRealName(realName);
        if (role != null && !role.isBlank()) u.setRole(role);
        if (enabled != null) u.setEnabled(enabled);

        User saved = userRepository.save(u);
        saved.setPasswordHash(null);
        log.info("[用户管理] 更新用户: {} -> role={}, enabled={}", username, saved.getRole(), saved.getEnabled());
        return saved;
    }

    /** 重置密码 */
    public void resetPassword(String username, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
        User u = userRepository.findById(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));

        u.setPasswordHash(encoder.encode(rawPassword));
        userRepository.save(u);
        log.info("[用户管理] 重置密码: {}", username);
    }

    /** 删除用户（内置 admin 账号保护） */
    public void deleteUser(String username) {
        if ("admin".equals(username)) {
            throw new IllegalArgumentException("内置管理员账号不允许删除");
        }
        if (!userRepository.existsById(username)) {
            throw new IllegalArgumentException("用户不存在: " + username);
        }
        userRepository.deleteById(username);
        log.warn("[用户管理] 删除用户: {}", username);
    }
}
