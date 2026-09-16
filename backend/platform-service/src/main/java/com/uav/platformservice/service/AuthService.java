package com.uav.platformservice.service;

import com.uav.platformservice.common.JwtUtil;
import com.uav.platformservice.model.User;
import com.uav.platformservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证服务（UC-13 用户登录）。
 * 【角色】Service 层
 * 【联系】MongoDB（校验用户）+ JWT 工具（签发令牌）
 * 【数据流】用户名密码 → 查库比对 BCrypt 哈希 → 签发 JWT → 返回 token + 角色
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    /** BCrypt：自带盐值的哈希算法，同一密码每次加密结果都不同 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 登录成功返回 {token, username, realName, role}；失败抛 IllegalArgumentException */
    public Map<String, Object> login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalArgumentException("账号已停用，请联系管理员");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        user.setLastLoginTime(System.currentTimeMillis());
        userRepository.save(user);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", JwtUtil.create(user.getUsername(), user.getRole()));
        result.put("username", user.getUsername());
        result.put("realName", user.getRealName());
        result.put("role", user.getRole());

        log.info("[登录] {} ({}) 登录成功", user.getUsername(), user.getRole());
        return result;
    }

    /** 密码加密（供用户管理复用） */
    public String encodePassword(String rawPassword) {
        return encoder.encode(rawPassword);
    }
}
