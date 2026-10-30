package com.acme.platform.auth.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 会话底账（7.3.2：审计 / 追溯持久层；运行时判定走 Redis 单会话索引与撤销集，本表不做逐请求查询——
 * 网关不查库 M-09）。不标 @TenantId（按 tokenId / userId 全局定位，Q-D04）；无软删（终态行清理第 12 章）。
 */
@Entity
@Table(name = "auth_session",
        uniqueConstraints = @UniqueConstraint(name = "uk_auth_session_token", columnNames = "token_id"),
        indexes = @Index(name = "idx_auth_session_user_status", columnList = "user_id, status"))
public class AuthSession extends AuditBase {

    @Column(name = "token_id", length = 64, nullable = false)
    private String tokenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "channel", length = 8, nullable = false)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private SessionStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 关联刷新令牌 jti（C 端双令牌；B 端为 null，consumer-auth-refresh-dataperm）。 */
    @Column(name = "refresh_token_id", length = 64)
    private String refreshTokenId;

    protected AuthSession() {
    }

    public AuthSession(String tokenId, Long userId, String tenantId, String channel,
                       Instant issuedAt, Instant expiresAt) {
        this.tokenId = tokenId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.channel = channel;
        this.status = SessionStatus.ACTIVE;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public String getTokenId() {
        return tokenId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getChannel() {
        return channel;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** 互踢 / 管理撤销终态。 */
    public void markKicked() {
        this.status = SessionStatus.KICKED;
    }

    /** 主动登出终态。 */
    public void markLogout() {
        this.status = SessionStatus.LOGOUT;
    }

    /** 是否终态（幂等登出判定）。 */
    public boolean isTerminal() {
        return status != SessionStatus.ACTIVE;
    }

    public String getRefreshTokenId() {
        return refreshTokenId;
    }

    /** 首次签发时绑定刷新令牌 jti（C 端）。 */
    public void bindRefreshToken(String refreshTokenId) {
        this.refreshTokenId = refreshTokenId;
    }

    /** 刷新轮换（consumer-auth-refresh-dataperm）：双 jti 换代；会话起点不变，底账到期跟随新刷新令牌。 */
    public void rotate(String newTokenId, String newRefreshTokenId, Instant refreshExpiresAt) {
        this.tokenId = newTokenId;
        this.refreshTokenId = newRefreshTokenId;
        this.expiresAt = refreshExpiresAt;
    }
}
