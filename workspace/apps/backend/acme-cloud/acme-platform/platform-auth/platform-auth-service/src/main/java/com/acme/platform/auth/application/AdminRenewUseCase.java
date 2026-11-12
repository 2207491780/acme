package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * B 端自续约用例（login-renew，滑动会话）：携带当前 Bearer 访问令牌换取同 claims 新令牌。
 * 校验链：验签（B 端 + access）→ 会话非终态（互踢/登出即拒）→ 撤销集查重（防重放）→
 * 同 claims 重签 → 旧 jti 撤销 + 底账换代（防旧令牌续用）。
 * 编排不开启整体事务（DB 换代小事务在 SessionRecorder；撤销集写入事务外 fail-open，取舍 #7）。
 */
@Service
public class AdminRenewUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminRenewUseCase.class);

    private final UserTokenIssuer tokenIssuer;
    private final SessionRedis sessionRedis;
    private final SessionRecorder sessionRecorder;

    public AdminRenewUseCase(UserTokenIssuer tokenIssuer, SessionRedis sessionRedis,
                             SessionRecorder sessionRecorder) {
        this.tokenIssuer = tokenIssuer;
        this.sessionRedis = sessionRedis;
        this.sessionRecorder = sessionRecorder;
    }

    /**
     * 自续约（需认证端点：前端在过期前 2 分钟调用；令牌过期即 401，前端转登录页）。
     * 返回新访问令牌（tokenType Bearer，claims 与旧令牌一致，jti 换代）。
     */
    public AdminTokenIssued renew(String oldAccessToken) {
        if (oldAccessToken == null || oldAccessToken.isBlank()) {
            throw new BizException(AuthError.REFRESH_TOKEN_INVALID);
        }
        UserTokenIssuer.VerifiedAccess verified = tokenIssuer.verifyAccess(oldAccessToken);
        if (!UserTokenIssuer.CHANNEL_B.equals(verified.channel())
                || !UserTokenIssuer.TOKEN_TYPE_ACCESS.equals(verified.tokenType())) {
            // 端别/类型守卫：C 端令牌与 refresh 型令牌不得走 B 端续约（密钥同域但 claims 隔离，11.6.2）
            throw new BizException(AuthError.REFRESH_TOKEN_INVALID);
        }
        if (sessionRecorder.isTerminal(verified.tokenId())) {
            // 互踢 / 登出后的旧令牌不得续约（会话终态）
            throw new BizException(AuthError.REFRESH_TOKEN_INVALID);
        }
        if (sessionRedis.isRevoked(verified.tokenId())) {
            // 撤销集查重：旧令牌已换代/登出（含并发重放）直接拒绝
            throw new BizException(AuthError.REFRESH_TOKEN_INVALID);
        }
        UserTokenIssuer.IssuedToken issued = tokenIssuer.issue(verified.userId(), verified.tenantId(),
                verified.roles());
        sessionRecorder.markLogout(verified.tokenId());
        long remaining = verified.expiresAt() == null ? 0
                : Math.max(0, Duration.between(Instant.now(), verified.expiresAt()).toSeconds());
        try {
            if (remaining > 0) {
                sessionRedis.revoke(verified.tokenId(), remaining);
            }
        } catch (RuntimeException ex) {
            // 撤销集写入失败 fail-open（取舍 #7）：旧令牌至多存活至原过期，换取可用性
            log.warn("自续约旧令牌撤销集写入失败：tokenId={}", verified.tokenId(), ex);
        }
        sessionRecorder.record(issued.tokenId(), verified.userId(), verified.tenantId(),
                UserTokenIssuer.CHANNEL_B, issued.issuedAt(), issued.expiresAt());
        long ttl = Math.max(1, Duration.between(Instant.now(), issued.expiresAt()).toSeconds());
        sessionRedis.indexSession(verified.userId(), issued.tokenId(), ttl);
        return new AdminTokenIssued(issued.token(), "Bearer", issued.expiresAt(), verified.userId(),
                null, null);
    }
}
