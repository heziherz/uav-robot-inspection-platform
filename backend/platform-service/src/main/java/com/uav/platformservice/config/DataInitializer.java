package com.uav.platformservice.config;

import com.uav.platformservice.model.User;
import com.uav.platformservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化默认账号（仅当 user 集合为空时执行）。
 * 三个账号对应需求文档中的三类参与者。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DataInitializer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;         // 已初始化过，跳过
        }

        create("admin", "admin123", "系统管理员", "ADMIN");
        create("operator", "123456", "巡检值班员", "OPERATOR");
        create("ops", "123456", "运维人员", "OPS");

        log.info("[初始化] 默认账号已创建 —— admin/admin123（管理员）、operator/123456（值班员）、ops/123456（运维）");
    }

    private void create(String username, String rawPassword, String realName, String role) {
        User u = new User();
        u.setUserId(username);                       // 用 username 作主键
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));   // BCrypt 加密后存储，不存明文
        u.setRealName(realName);
        u.setRole(role);
        u.setEnabled(true);
        u.setCreateTime(System.currentTimeMillis());
        userRepository.save(u);
    }
}
