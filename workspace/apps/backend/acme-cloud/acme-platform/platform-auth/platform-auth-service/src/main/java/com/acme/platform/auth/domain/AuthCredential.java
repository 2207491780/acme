package com.acme.platform.auth.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 凭据（7.3.1：不标 @TenantId——按 user_id 唯一定位，登录链路无租户上下文，Q-D04；tenant_id 为归属标注列）。
 * passwordHash 不得出现在任何 DTO / 日志（FR-005、11.5.2 白名单式视图）。
 * 无软删——凭据随重置覆盖 upsert（孤儿清理登记架构设计第 12 章）。
 */
@Entity
@Table(name = "auth_credential", uniqueConstraints = @UniqueConstraint(name = "uk_auth_credential_user",
        columnNames = "user_id"))
public class AuthCredential extends AuditBase {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "password_updated_at", nullable = false)
    private Instant passwordUpdatedAt;

    protected AuthCredential() {
    }

    public AuthCredential(Long userId, String tenantId, String passwordHash) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.passwordHash = passwordHash;
        this.passwordUpdatedAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    /** 散列值（仅供应用层校验 / 持久化，禁止外泄任何 DTO）。 */
    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    /** 重置散列（upsert 语义：保留既有归属标注）。 */
    public void reset(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.passwordUpdatedAt = Instant.now();
    }
}
