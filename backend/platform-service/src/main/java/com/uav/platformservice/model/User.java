package com.uav.platformservice.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 平台用户（MongoDB 集合 user）。
 *
 * 角色（role）取值：
 *   OPERATOR —— 巡检值班员（业务主用户：态势、任务、告警、检索）
 *   ADMIN    —— 系统管理员（设备台账、用户与权限）
 *   OPS      —— 系统运维人员（组件健康、日志、备份恢复、Kibana）
 */
@Document("user")
public class User {

    @Id
    private String userId;          // 用 username 作主键（唯一）
    private String username;
    private String passwordHash;    // BCrypt 加密后的密码（不存明文）
    private String realName;
    private String role;            // OPERATOR / ADMIN / OPS
    private Boolean enabled;        // 是否启用
    private Long createTime;
    private Long lastLoginTime;

    public User() {
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Long getCreateTime() { return createTime; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }

    public Long getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(Long lastLoginTime) { this.lastLoginTime = lastLoginTime; }
}
