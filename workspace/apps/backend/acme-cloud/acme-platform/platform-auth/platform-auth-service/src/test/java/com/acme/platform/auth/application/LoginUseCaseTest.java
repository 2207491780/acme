package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.system.api.AuthProfileView;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录用例测试（FR-001/002；AC-006 登录分支矩阵：验证码前置 / 成功 / 统一失败 / 锁定 / 停用 /
 * 互踢后旧令牌撤销 / Redis fail-open）。
 */
class LoginUseCaseTest {

    private static final String SECRET = "login-usecase-test-secret-0123456789abcdef";

    private final AuthProfileGateway authProfileGateway = mock(AuthProfileGateway.class);
    private final AuthCredentialRepository credentialRepository = mock(AuthCredentialRepository.class);
    private final LoginGuardRedis loginGuard = mock(LoginGuardRedis.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final ImageCaptchaUseCase imageCaptchaUseCase = mock(ImageCaptchaUseCase.class);
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10);

    private LoginUseCase useCase;

    private final String passwordHash = encoder.encode("Correct@123");

    /** 图形验证码通过（Mockito void 默认不抛即通过）。 */
    private static final String CAPTCHA_ID = "captcha-id-1";
    private static final String CAPTCHA_CODE = "a1b2";

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setTtlSeconds(1800);
        useCase = new LoginUseCase(authProfileGateway, credentialRepository, loginGuard, sessionRedis,
                sessionRecorder, new UserTokenIssuer(properties), encoder, imageCaptchaUseCase);
    }

    private AuthProfileView profile(String status) {
        return new AuthProfileView("1001", "admin", "超级管理员", "default", status, Set.of("super_admin"));
    }

    private void stubLoginSuccessPath() {
        when(authProfileGateway.findByUsername("admin")).thenReturn(profile("ENABLED"));
        when(credentialRepository.findByUserId(1001L)).thenReturn(new AuthCredential(1001L, "default", passwordHash));
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of());
    }

    @Test
    void imageCaptchaRejected401BeforeCredentialLookup() {
        // 图形验证码前置（login-guard-captcha）：失败即拒，且不触发画像查询（防爆破前置）
        doThrow(new BizException(AuthError.CAPTCHA_INVALID)).when(imageCaptchaUseCase)
                .verify(CAPTCHA_ID, CAPTCHA_CODE);
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_INVALID.code()));
        verify(authProfileGateway, never()).findByUsername(anyString());
    }

    @Test
    void successIssuesTokenAndRecordsSession() {
        stubLoginSuccessPath();
        AdminTokenIssued issued = useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE);
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.userId()).isEqualTo(1001L);
        assertThat(issued.expiresAt()).isAfter(Instant.now());
        verify(imageCaptchaUseCase).verify(CAPTCHA_ID, CAPTCHA_CODE);
        verify(sessionRecorder).record(anyString(), eq(1001L), eq("default"), eq("B"), any(Instant.class),
                any(Instant.class));
        verify(sessionRedis).indexSession(eq(1001L), anyString(), anyLong());
        verify(loginGuard).clearFailures("admin");
    }

    @Test
    void wrongPasswordUnifiedLoginFailed() {
        stubLoginSuccessPath();
        when(loginGuard.recordFailure("admin")).thenReturn(false);
        assertThatThrownBy(() -> useCase.login("default", "admin", "Wrong@12345", CAPTCHA_ID, CAPTCHA_CODE))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.LOGIN_FAILED.code()));
    }

    @Test
    void missingCredentialUnifiedLoginFailed() {
        // 未设置密码与凭证错误统一失败（FR-005：不泄露账号存在性差异）
        when(authProfileGateway.findByUsername("admin")).thenReturn(profile("ENABLED"));
        when(credentialRepository.findByUserId(1001L)).thenReturn(null);
        when(loginGuard.recordFailure("admin")).thenReturn(false);
        assertThatThrownBy(() -> useCase.login("default", "admin", "whatever1", CAPTCHA_ID, CAPTCHA_CODE))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.LOGIN_FAILED.code()));
    }

    @Test
    void lockedPrecheckRejected423() {
        when(loginGuard.isLocked("admin")).thenReturn(true);
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.ACCOUNT_LOCKED.code()));
        verify(authProfileGateway, never()).findByUsername(anyString());
    }

    @Test
    void failureThresholdTriggersLock423() {
        stubLoginSuccessPath();
        when(loginGuard.recordFailure("admin")).thenReturn(true);
        assertThatThrownBy(() -> useCase.login("default", "admin", "Wrong@12345", CAPTCHA_ID, CAPTCHA_CODE))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.ACCOUNT_LOCKED.code()));
    }

    @Test
    void disabledAccountRejected403() {
        when(authProfileGateway.findByUsername("admin")).thenReturn(profile("DISABLED"));
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.ACCOUNT_DISABLED.code()));
    }

    @Test
    void kickPreviousSessionsAndRevokeOldTokens() {
        stubLoginSuccessPath();
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of(
                new SessionRecorder.RevocableToken("old-jti", Instant.now().plusSeconds(600))));
        AdminTokenIssued issued = useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE);
        assertThat(issued.accessToken()).isNotBlank();
        verify(sessionRedis).revoke(eq("old-jti"), anyLong());
    }

    @Test
    void kickRevokeFailureDoesNotBlockLogin() {
        // 互踢撤销集写入失败降级 WARN（fail-open，取舍 #7），登录仍成功
        stubLoginSuccessPath();
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of(
                new SessionRecorder.RevocableToken("old-jti", Instant.now().plusSeconds(600))));
        doThrow(new SessionRedis.RedisKeyWriteFailedException("old-jti", null))
                .when(sessionRedis).revoke(eq("old-jti"), anyLong());
        assertThat(useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE).accessToken()).isNotBlank();
    }

    @Test
    void tenantMismatchUnifiedLoginFailed() {
        // 多租户口径（login-tenant-select）：所选租户与账号归属不一致按统一失败（FR-005 不泄露归属）
        stubLoginSuccessPath();
        assertThatThrownBy(() -> useCase.login("other-tenant", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.LOGIN_FAILED.code()));
    }
}
