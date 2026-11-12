package com.acme.platform.auth.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.Instant;

/**
 * C 端消费者账户（consumer-auth-refresh-dataperm：手机号验证码登录即注册）：
 * 凭据域闭环——账户与凭据（验证码）归 platform-auth，用户档案归后续 consumer 业务服务（读模型/事件同步）。
 * 不标租户列（C 端逻辑租户为保留值 consumer，登录链路无租户上下文，Q-D04 同型）；无软删（停用即状态位）。
 */
@Table("auth_consumer_account")
public class AuthConsumerAccount extends AuditBase {

    @Column("phone")
    private String phone;

    @Column("status")
    private ConsumerStatus status;

    @Column("registered_at")
    private Instant registeredAt;

    @Column("last_login_at")
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
