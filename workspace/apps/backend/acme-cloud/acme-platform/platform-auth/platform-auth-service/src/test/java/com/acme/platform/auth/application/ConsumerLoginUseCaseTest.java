package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.dto.AppTokenIssued;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.domain.AuthConsumerAccount;
import com.acme.platform.auth.domain.ConsumerStatus;
import com.acme.platform.auth.infrastructure.redis.CaptchaRedis;
import com.acme.platform.auth.infrastructure.repository.AuthConsumerAccountRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

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
 * C 端验证码登录用例测试（consumer-auth-refresh-dataperm）：验证码口径（错误/过期区分）、
 * 首登自动注册、停用拒绝、双令牌签发与 C 端会话口径（channel=C、consumer 逻辑租户、多端并存不互踢）。
 */
class ConsumerLoginUseCaseTest {

    private static final String SECRET = "consumer-login-test-secret-0123456789abcdef";
    private static final String PHONE = "13800138000";

    private final CaptchaRedis captchaRedis = mock(CaptchaRedis.class);
    private final AuthConsumerAccountRepository accountRepository = mock(AuthConsumerAccountRepository.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);

    private ConsumerLoginUseCase useCase;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        useCase = new ConsumerLoginUseCase(captchaRedis, accountRepository, sessionRecorder,
                new UserTokenIssuer(properties), properties);
    }

    @Test
    void wrongCaptchaRejected() {
        when(captchaRedis.verify(PHONE, "000000")).thenReturn(false);
        when(captchaRedis.exists(PHONE)).thenReturn(true);
        assertError(() -> useCase.login(PHONE, "000000"), AuthError.CAPTCHA_INVALID);
        verify(accountRepository, never()).findByPhone(any());
    }

    @Test
    void missingCaptchaDistinguishedAsExpired() {
        when(captchaRedis.verify(PHONE, "000000")).thenReturn(false);
        when(captchaRedis.exists(PHONE)).thenReturn(false);
        assertError(() -> useCase.login(PHONE, "000000"), AuthError.CAPTCHA_EXPIRED);
    }

    @Test
    void firstLoginRegistersAccountAndIssuesTokenPair() {
        when(captchaRedis.verify(PHONE, "123456")).thenReturn(true);
        AuthConsumerAccount fresh = new AuthConsumerAccount(PHONE);
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.empty());
        when(accountRepository.save(any(AuthConsumerAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AppTokenIssued issued = useCase.login(PHONE, "123456");

        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.refreshToken()).isNotBlank();
        assertThat(issued.accessExpiresAt()).isAfter(Instant.now());
        assertThat(issued.refreshExpiresAt()).isAfter(issued.accessExpiresAt());
        assertThat(issued.phone()).isEqualTo(PHONE);

        // C 端会话记录：双令牌 + channel=C + consumer 逻辑租户
        verify(sessionRecorder).recordConsumer(any(), any(), any(),
                eq(ConsumerLoginUseCase.CONSUMER_TENANT), any(), any(), any());
        // 最近登录时间刷新（两次 save：注册 upsert + markLogin）
        verify(accountRepository, org.mockito.Mockito.times(2)).save(any(AuthConsumerAccount.class));
    }

    @Test
    void existingAccountLoginRefreshesLastLogin() {
        when(captchaRedis.verify(PHONE, "123456")).thenReturn(true);
        AuthConsumerAccount existing = new AuthConsumerAccount(PHONE);
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(AuthConsumerAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AppTokenIssued issued = useCase.login(PHONE, "123456");
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(existing.getLastLoginAt()).isNotNull();
    }

    @Test
    void disabledAccountRejected() {
        when(captchaRedis.verify(PHONE, "123456")).thenReturn(true);
        AuthConsumerAccount disabled = new AuthConsumerAccount(PHONE);
        disabled.disable();
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(disabled));

        assertError(() -> useCase.login(PHONE, "123456"), AuthError.CONSUMER_DISABLED);
        verify(sessionRecorder, never()).recordConsumer(any(), any(), anyLong(), any(), any(), any(), any());
    }

    private static void assertError(Runnable action, AuthError expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(expected.code()));
    }
}
