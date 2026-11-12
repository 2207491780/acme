package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 登出用例（FR-004，架构设计 4.3；BR-006 即时失效）：
 * 撤销集写入失败 → LOGOUT_FAILED（500）——不得出现「响应成功但令牌仍可用」状态；
 * 已失效令牌的登出按幂等成功处理（10.3.1：撤销集已有 jti 或会话已终态 → 直接成功）。
 */
@Service
public class LogoutUseCase {

    private final SessionRedis sessionRedis;
    private final SessionRecorder sessionRecorder;
    private final UserTokenIssuer tokenIssuer;

    public LogoutUseCase(SessionRedis sessionRedis, SessionRecorder sessionRecorder,
                         UserTokenIssuer tokenIssuer) {
        this.sessionRedis = sessionRedis;
        this.sessionRecorder = sessionRecorder;
        this.tokenIssuer = tokenIssuer;
    }

    /** 登出（bearerToken=请求携带的当前访问令牌；网关已验签，此处自校验取 jti / exp）。
     * C 端双令牌会话：关联刷新令牌一并撤销（consumer-auth-refresh-dataperm）。 */
    public void logout(String bearerToken) {
        UserTokenIssuer.VerifiedToken verified = tokenIssuer.verify(bearerToken);
        // 幂等判定（10.3.1）：撤销集已有该 jti 或会话已终态 → 直接成功；查询异常按未撤销继续（写入侧兜底）
        if (alreadyRevoked(verified.tokenId()) || sessionRecorder.isTerminal(verified.tokenId())) {
            return;
        }
        long remaining = verified.expiresAt() == null ? 1
                : Math.max(1, Duration.between(Instant.now(), verified.expiresAt()).toSeconds());
        try {
            sessionRedis.revoke(verified.tokenId(), remaining);
            // C 端会话关联的刷新令牌同撤（无则跳过；撤销失败由旧刷新令牌自然过期兜底）
            sessionRecorder.refreshTokenOf(verified.tokenId())
                    .ifPresent(refreshTokenId -> sessionRedis.revoke(refreshTokenId, remaining));
        } catch (RuntimeException ex) {
            throw new BizException(AuthError.LOGOUT_FAILED);
        }
        sessionRecorder.markLogout(verified.tokenId());
    }

    private boolean alreadyRevoked(String tokenId) {
        try {
            return sessionRedis.isRevoked(tokenId);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
