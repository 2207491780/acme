package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.auth.config.AuthTokenProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登出用例测试（FR-004 / BR-006：即时失效写入撤销集、幂等重放成功、Redis 失败 LOGOUT_FAILED）。
 */
class LogoutUseCaseTest {

    private static final String SECRET = "logout-usecase-test-secret-0123456789abcdef";

    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private UserTokenIssuer issuer;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setTtlSeconds(1800);
        issuer = new UserTokenIssuer(properties);
    }

    @Test
    void logoutRevokesTokenAndMarksSession() {
        UserTokenIssuer.IssuedToken issued = issuer.issue(1001L, "default", java.util.Set.of("super_admin"));
        when(sessionRedis.isRevoked(issued.tokenId())).thenReturn(false);
        when(sessionRecorder.isTerminal(issued.tokenId())).thenReturn(false);
        new LogoutUseCase(sessionRedis, sessionRecorder, issuer).logout(issued.token());
        verify(sessionRedis).revoke(eq(issued.tokenId()), anyLong());
        verify(sessionRecorder).markLogout(issued.tokenId());
    }

    @Test
    void replayOfAlreadyRevokedTokenIsIdempotentSuccess() {
        // 10.3.1 幂等：撤销集已有 jti → 直接成功不再写
        UserTokenIssuer.IssuedToken issued = issuer.issue(1001L, "default", java.util.Set.of());
        when(sessionRedis.isRevoked(issued.tokenId())).thenReturn(true);
        assertThatCode(() ->
                new LogoutUseCase(sessionRedis, sessionRecorder, issuer).logout(issued.token()))
                .doesNotThrowAnyException();
        verify(sessionRedis, never()).revoke(anyString(), anyLong());
    }

    @Test
    void terminalSessionLogoutIsIdempotentSuccess() {
        UserTokenIssuer.IssuedToken issued = issuer.issue(1001L, "default", java.util.Set.of());
        when(sessionRedis.isRevoked(issued.tokenId())).thenReturn(false);
        when(sessionRecorder.isTerminal(issued.tokenId())).thenReturn(true);
        new LogoutUseCase(sessionRedis, sessionRecorder, issuer).logout(issued.token());
        verify(sessionRedis, never()).revoke(anyString(), anyLong());
    }

    @Test
    void revokeWriteFailureFailsLogout500() {
        // FR-004 异常分支：不得出现「响应成功但令牌仍可用」
        UserTokenIssuer.IssuedToken issued = issuer.issue(1001L, "default", java.util.Set.of());
        when(sessionRedis.isRevoked(issued.tokenId())).thenReturn(false);
        when(sessionRecorder.isTerminal(issued.tokenId())).thenReturn(false);
        doThrow(new SessionRedis.RedisKeyWriteFailedException(issued.tokenId(), null))
                .when(sessionRedis).revoke(anyString(), anyLong());
        assertThatThrownBy(() -> new LogoutUseCase(sessionRedis, sessionRecorder, issuer).logout(issued.token()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.LOGOUT_FAILED.code()));
    }

    @Test
    void invalidTokenRejectedUnauthorized() {
        LogoutUseCase useCase = new LogoutUseCase(sessionRedis, sessionRecorder, issuer);
        assertThatThrownBy(() -> useCase.logout("not-a-jws-token"))
                .isInstanceOf(BizException.class);
    }
}
