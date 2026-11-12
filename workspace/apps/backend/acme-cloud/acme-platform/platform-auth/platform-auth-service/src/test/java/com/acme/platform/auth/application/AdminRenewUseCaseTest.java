package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B 端自续约用例测试（login-renew，滑动会话）：同 claims 重签 / 端别与类型守卫 /
 * 会话终态拒绝 / 撤销集重放拒绝 / 撤销写入失败 fail-open。
 */
class AdminRenewUseCaseTest {

    private static final String SECRET = "admin-renew-test-secret-0123456789abcdef";

    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final UserTokenIssuer issuer = new UserTokenIssuer(properties());

    private AdminRenewUseCase useCase;
    private UserTokenIssuer.IssuedToken old;

    private static AuthTokenProperties properties() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setTtlSeconds(1800);
        return properties;
    }

    @BeforeEach
    void setUp() {
        useCase = new AdminRenewUseCase(issuer, sessionRedis, sessionRecorder);
        // 真实签发旧访问令牌（B 端，端到端链路：issue → verifyAccess → 重签）
        old = issuer.issue(1001L, "default", Set.of("super_admin"));
    }

    @Test
    void renewRotatesTokenAndLedgerWithSameClaims() {
        AdminTokenIssued issued = useCase.renew(old.token());
        assertThat(issued.accessToken()).isNotBlank().isNotEqualTo(old.token());
        assertThat(issued.userId()).isEqualTo(1001L);
        verify(sessionRecorder).markLogout(old.tokenId());
        verify(sessionRedis).revoke(eq(old.tokenId()), anyLong());
        verify(sessionRecorder).record(anyString(), eq(1001L), eq("default"), eq("B"),
                any(Instant.class), any(Instant.class));
        verify(sessionRedis).indexSession(eq(1001L), anyString(), anyLong());
    }

    @Test
    void renewRejectsCChannelToken() {
        // 端别守卫：C 端令牌不得走 B 端续约（claims 隔离 11.6.2）
        UserTokenIssuer.IssuedToken cToken = issuer.issue(9001L, "consumer", Set.of(),
                UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_ACCESS, 7200);
        assertThatThrownBy(() -> useCase.renew(cToken.token()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.REFRESH_TOKEN_INVALID.code()));
    }

    @Test
    void renewRejectsRefreshTypeToken() {
        // 类型守卫：refresh 型令牌不得走自续约
        UserTokenIssuer.IssuedToken refresh = issuer.issue(1001L, "default", Set.of(),
                UserTokenIssuer.CHANNEL_B, UserTokenIssuer.TOKEN_TYPE_REFRESH, 600);
        assertThatThrownBy(() -> useCase.renew(refresh.token()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.REFRESH_TOKEN_INVALID.code()));
    }

    @Test
    void renewRejectsTerminalSession() {
        when(sessionRecorder.isTerminal(old.tokenId())).thenReturn(true);
        assertThatThrownBy(() -> useCase.renew(old.token()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.REFRESH_TOKEN_INVALID.code()));
    }

    @Test
    void renewRejectsRevokedToken() {
        when(sessionRedis.isRevoked(old.tokenId())).thenReturn(true);
        assertThatThrownBy(() -> useCase.renew(old.token()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.REFRESH_TOKEN_INVALID.code()));
    }

    @Test
    void renewRejectsMissingToken() {
        assertThatThrownBy(() -> useCase.renew(" "))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.REFRESH_TOKEN_INVALID.code()));
    }

    @Test
    void renewRevocationWriteFailureStillIssuesToken() {
        // 旧令牌撤销集写入失败 fail-open（取舍 #7）：新令牌照常签发，旧令牌至多存活至原过期
        doThrow(new SessionRedis.RedisKeyWriteFailedException(old.tokenId(), null))
                .when(sessionRedis).revoke(eq(old.tokenId()), anyLong());
        AdminTokenIssued issued = useCase.renew(old.token());
        assertThat(issued.accessToken()).isNotBlank();
    }
}
