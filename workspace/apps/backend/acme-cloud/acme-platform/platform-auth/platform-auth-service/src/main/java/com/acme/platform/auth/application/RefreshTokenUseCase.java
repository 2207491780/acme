package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.dto.AppTokenIssued;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 刷新令牌用例（consumer-auth-refresh-dataperm，11.4：C 端一次性轮换）：
 * 验签 + tokenType/channel 口径判定 + 撤销集查重（重复使用即拒绝，fail-closed）→
 * 定位活跃会话取签发主体 → 签发新对 → 会话底账换代 → 旧对双撤销（旧 refresh jti + 旧 access jti，
 * 剩余 TTL）。编排不开启整体事务（DB 轮换小事务在 SessionRecorder；撤销集写入事务外，登出同口径）。
 */
@Service
public class RefreshTokenUseCase {

    private final UserTokenIssuer tokenIssuer;
    private final SessionRedis sessionRedis;
    private final SessionRecorder sessionRecorder;
    private final AuthTokenProperties.Consumer consumerProperties;

    public RefreshTokenUseCase(UserTokenIssuer tokenIssuer, SessionRedis sessionRedis,
                               SessionRecorder sessionRecorder, AuthTokenProperties tokenProperties) {
        this.tokenIssuer = tokenIssuer;
        this.sessionRedis = sessionRedis;
        this.sessionRecorder = sessionRecorder;
        this.consumerProperties = tokenProperties.getConsumer();
    }

    /** 刷新轮换（公开端点自证：验签失败 / 类型不符 / 已使用 / 会话终态 → REFRESH_TOKEN_INVALID 401）。 */
    public AppTokenIssued refresh(String refreshToken) {
        UserTokenIssuer.VerifiedToken verified;
        try {
            verified = tokenIssuer.verify(refreshToken);
        } catch (BizException ex) {
            throw new BizException(AuthError.REFRESH_TOKEN_INVALID);
        }
        if (!UserTokenIssuer.TOKEN_TYPE_REFRESH.equals(verified.tokenType())
                || !UserTokenIssuer.CHANNEL_C.equals(verified.channel())) {
            throw new BizException(AuthError.REFRESH_TOKEN_INVALID);
        }
        if (alreadyRevoked(verified.tokenId())) {
            // 一次性轮换：重复使用旧刷新令牌（含并发重放）直接拒绝
            throw new BizException(AuthError.REFRESH_TOKEN_INVALID);
        }
        AuthSession session = sessionRecorder.activeByRefreshToken(verified.tokenId())
                .orElseThrow(() -> new BizException(AuthError.REFRESH_TOKEN_INVALID));

        UserTokenIssuer.IssuedToken access = tokenIssuer.issue(session.getUserId(), session.getTenantId(),
                Set.of(), UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_ACCESS,
                consumerProperties.getAccessTtlSeconds());
        UserTokenIssuer.IssuedToken refresh = tokenIssuer.issue(session.getUserId(), session.getTenantId(),
                Set.of(), UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_REFRESH,
                consumerProperties.getRefreshTtlSeconds());
        sessionRecorder.rotateByRefreshToken(verified.tokenId(), access.tokenId(), refresh.tokenId(),
                refresh.expiresAt());
        revokeWithRemainingTtl(verified.tokenId(), verified.expiresAt());
        revokeWithRemainingTtl(session.getTokenId(), null);
        return new AppTokenIssued(access.token(), refresh.token(),
                access.expiresAt(), refresh.expiresAt(), session.getUserId(), null);
    }

    /** 撤销集不可读：按已撤销处理（fail-closed，防重放）。 */
    private boolean alreadyRevoked(String tokenId) {
        try {
            return sessionRedis.isRevoked(tokenId);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** 按 jti 撤销（剩余 TTL 自然回收；写入失败不阻断刷新——旧令牌至多存活至原过期，与互踢同口径）。 */
    private void revokeWithRemainingTtl(String tokenId, Instant expiresAt) {
        long remaining = expiresAt == null ? 1
                : Math.max(1, Duration.between(Instant.now(), expiresAt).toSeconds());
        try {
            sessionRedis.revoke(tokenId, remaining);
        } catch (RuntimeException ex) {
            // best-effort：刷新已成功，旧令牌自然过期兜底
        }
    }
}
