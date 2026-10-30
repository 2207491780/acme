package com.acme.platform.auth.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * C 端消费者账户（consumer-auth-refresh-dataperm：手机号验证码登录即注册）：
 * 凭据域闭环——账户与凭据（验证码）归 platform-auth，用户档案归后续 consumer 业务服务（读模型/事件同步）。
 * 不标 @TenantId（C 端逻辑租户为保留值 consumer，登录链路无租户上下文，Q-D04 同型）；无软删（停用即状态位）。
 */
@Entity
@Table(name = "auth_consumer_account", uniqueConstraints = @UniqueConstraint(name = "uk_auth_consumer_phone",
        columnNames = "phone"))
public class AuthConsumerAccount extends AuditBase {

    @Column(name = "phone", length = 16, nullable = false)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ConsumerStatus status;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AuthConsumerAccount() {
    }

    public AuthConsumerAccount(String phone) {
        this.phone = phone;
        this.status = ConsumerStatus.ENABLED;
        this.registeredAt = Instant.now();
    }

    public String getPhone() {
        return phone;
    }

    public ConsumerStatus getStatus() {
        return status;
    }

    public void disable() {
        this.status = ConsumerStatus.DISABLED;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /** 登录成功刷新最近登录时间。 */
    public void markLogin() {
        this.lastLoginAt = Instant.now();
    }
}
