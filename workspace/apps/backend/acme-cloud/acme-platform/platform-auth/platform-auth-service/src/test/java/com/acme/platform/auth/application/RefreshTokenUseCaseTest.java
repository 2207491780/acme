package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.dto.AppTokenIssued;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刷新令牌用例测试（consumer-auth-refresh-dataperm）：一次性轮换（重复使用/类型不符/验签失败 →
 * REFRESH_TOKEN_INVALID）、会话定位与双撤销、新对签发。
 */
class RefreshTokenUseCaseTest {

    private static final String SECRET = "refresh-usecase-test-secret-0123456789abcdef";

    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private UserTokenIssuer issuer;
    private RefreshTokenUseCase useCase;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        issuer = new UserTokenIssuer(properties);
        useCase = new RefreshTokenUseCase(issuer, sessionRedis, sessionRecorder, properties);
    }

    @Test
    void rotatesTokenPairAndRevokesOldPair() {
        UserTokenIssuer.IssuedToken oldRefresh = issuer.issue(9L, "consumer", Set.of(),
                UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_REFRESH, 1209600);
        when(sessionRedis.isRevoked(oldRefresh.tokenId())).thenReturn(false);
        AuthSession session = session(9L, oldRefresh.tokenId());
        when(sessionRecorder.activeByRefreshToken(oldRefresh.tokenId())).thenReturn(Optional.of(session));

        AppTokenIssued issued = useCase.refresh(oldRefresh.token());

        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.refreshToken()).isNotBlank();
        assertThat(issued.refreshToken()).isNotEqualTo(oldRefresh.token());
        // 会话底账换代 + 旧对双撤销（旧 refresh jti + 旧 access jti）
        verify(sessionRecorder).rotateByRefreshToken(eq(oldRefresh.tokenId()), any(), any(), any());
        verify(sessionRedis).revoke(eq(oldRefresh.tokenId()), anyLong());
        verify(sessionRedis).revoke(eq(session.getTokenId()), anyLong());
    }

    @Test
    void reuseOfRotatedRefreshTokenRejected() {
        UserTokenIssuer.IssuedToken oldRefresh = issuer.issue(9L, "consumer", Set.of(),
                UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_REFRESH, 1209600);
        when(sessionRedis.isRevoked(oldRefresh.tokenId())).thenReturn(true);

        assertInvalid(oldRefresh.token());
        verify(sessionRecorder, never()).activeByRefreshToken(any());
    }

    @Test
    void accessTokenUsedAsRefreshRejected() {
        UserTokenIssuer.IssuedToken access = issuer.issue(9L, "consumer", Set.of(),
                UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_ACCESS, 7200);
        when(sessionRedis.isRevoked(access.tokenId())).thenReturn(false);

        assertInvalid(access.token());
    }

    @Test
    void bChannelRefreshTokenRejected() {
        UserTokenIssuer.IssuedToken bToken = issuer.issue(9L, "consumer", Set.of(),
                UserTokenIssuer.CHANNEL_B, UserTokenIssuer.TOKEN_TYPE_REFRESH, 1209600);
        when(sessionRedis.isRevoked(bToken.tokenId())).thenReturn(false);

        assertInvalid(bToken.token());
    }

    @Test
    void garbageTokenRejected() {
        assertInvalid("not-a-jws-token");
    }

    @Test
    void unknownSessionRejected() {
        UserTokenIssuer.IssuedToken oldRefresh = issuer.issue(9L, "consumer", Set.of(),
                UserTokenIssuer.CHANNEL_C, UserTokenIssuer.TOKEN_TYPE_REFRESH, 1209600);
        when(sessionRedis.isRevoked(oldRefresh.tokenId())).thenReturn(false);
        when(sessionRecorder.activeByRefreshToken(oldRefresh.tokenId())).thenReturn(Optional.empty());

        assertInvalid(oldRefresh.token());
    }

    private static AuthSession session(long userId, String refreshTokenId) {
        AuthSession session = new AuthSession("access-jti-old", userId, "consumer", "C",
                Instant.now(), Instant.now().plusSeconds(1209600));
        session.bindRefreshToken(refreshTokenId);
        return session;
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> useCase.refresh(token))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.REFRESH_TOKEN_INVALID.code()));
    }
}
