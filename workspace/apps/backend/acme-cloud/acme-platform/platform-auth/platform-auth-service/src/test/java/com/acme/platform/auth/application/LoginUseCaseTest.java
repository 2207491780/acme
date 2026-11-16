package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.CaptchaPolicyView;
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
 * REQ-20261117-011514：验证码改按租户策略条件校验——关闭跳过 / 开启照常 / 查询失败 fail-closed。
 */
class LoginUseCaseTest {

    private static final String SECRET = "login-usecase-test-secret-0123456789abcdef";

    private final AuthProfileGateway authProfileGateway = mock(AuthProfileGateway.class);
    private final AuthCredentialRepository credentialRepository = mock(AuthCredentialRepository.class);
    private final LoginGuardRedis loginGuard = mock(LoginGuardRedis.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final ImageCaptchaUseCase imageCaptchaUseCase = mock(ImageCaptchaUseCase.class);
    private final LoginLogRecorder loginLogRecorder = mock(LoginLogRecorder.class);
    private final SystemConfigGateway systemConfigGateway = mock(SystemConfigGateway.class);
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
                sessionRecorder, new UserTokenIssuer(properties), encoder, imageCaptchaUseCase,
                loginLogRecorder, systemConfigGateway);
        // 既有用例基线：策略开启（REQ-20261117-011514 前的全局强制口径）；策略用例可覆盖
        when(systemConfigGateway.findCaptchaPolicy(anyString())).thenReturn(new CaptchaPolicyView(true));
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
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_INVALID.code()));
        verify(authProfileGateway, never()).findByUsername(anyString());
    }

    @Test
    void successIssuesTokenAndRecordsSession() {
        stubLoginSuccessPath();
        AdminTokenIssued issued = useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit");
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
        assertThatThrownBy(() -> useCase.login("default", "admin", "Wrong@12345", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
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
        assertThatThrownBy(() -> useCase.login("default", "admin", "whatever1", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.LOGIN_FAILED.code()));
    }

    @Test
    void lockedPrecheckRejected423() {
        when(loginGuard.isLocked("admin")).thenReturn(true);
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.ACCOUNT_LOCKED.code()));
        verify(authProfileGateway, never()).findByUsername(anyString());
    }

    @Test
    void failureThresholdTriggersLock423() {
        stubLoginSuccessPath();
        when(loginGuard.recordFailure("admin")).thenReturn(true);
        assertThatThrownBy(() -> useCase.login("default", "admin", "Wrong@12345", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.ACCOUNT_LOCKED.code()));
    }

    @Test
    void disabledAccountRejected403() {
        when(authProfileGateway.findByUsername("admin")).thenReturn(profile("DISABLED"));
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.ACCOUNT_DISABLED.code()));
    }

    @Test
    void kickPreviousSessionsAndRevokeOldTokens() {
        stubLoginSuccessPath();
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of(
                new SessionRecorder.RevocableToken("old-jti", Instant.now().plusSeconds(600))));
        AdminTokenIssued issued = useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit");
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
        assertThat(useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit").accessToken()).isNotBlank();
    }

    @Test
    void tenantMismatchUnifiedLoginFailed() {
        // 多租户口径（login-tenant-select）：所选租户与账号归属不一致按统一失败（FR-005 不泄露归属）
        stubLoginSuccessPath();
        assertThatThrownBy(() -> useCase.login("other-tenant", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.LOGIN_FAILED.code()));
    }

    // ---------- REQ-20261117-011514：验证码策略条件校验（AC-2/AC-3/AC-6 用例级口径） ----------

    @Test
    void should_skipCaptchaVerifyAndIssueToken_when_policyDisabled() {
        // AC-2：租户开关关闭 → 不携带验证码凭正确凭据可登录，行为同关闭前有效凭据路径
        when(systemConfigGateway.findCaptchaPolicy("default")).thenReturn(new CaptchaPolicyView(false));
        stubLoginSuccessPath();
        AdminTokenIssued issued = useCase.login("default", "admin", "Correct@123", null, null, "10.0.0.1", "JUnit");
        assertThat(issued.accessToken()).isNotBlank();
        verify(imageCaptchaUseCase, never()).verify(any(), any());
    }

    @Test
    void should_skipCaptchaVerifyEvenWhenCaptchaGarbage_when_policyDisabled() {
        // AC-2 关闭语义：后端跳过校验（不调用 verify），即使客户端误带验证码字段也不拒绝
        when(systemConfigGateway.findCaptchaPolicy("default")).thenReturn(new CaptchaPolicyView(false));
        stubLoginSuccessPath();
        assertThat(useCase.login("default", "admin", "Correct@123", "bad-id", "bad-code", "10.0.0.1", "JUnit")
                .accessToken()).isNotBlank();
        verify(imageCaptchaUseCase, never()).verify(any(), any());
    }

    @Test
    void should_rejectLoginWithCaptchaError_when_policyEnabledAndCaptchaMissing() {
        // AC-3：租户开关开启 → 缺失验证码（字段缺省 null）即拒（blank 守卫既有错误码 INVALID，不调用 verify），
        // 且不触发画像查询（防爆破前置口径不变）
        when(systemConfigGateway.findCaptchaPolicy("default")).thenReturn(new CaptchaPolicyView(true));
        stubLoginSuccessPath();
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", null, null, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_INVALID.code()));
        verify(imageCaptchaUseCase, never()).verify(any(), any());
        verify(authProfileGateway, never()).findByUsername(anyString());
    }

    @Test
    void should_rejectBlankCaptchaFields_when_policyEnabled() {
        // REQ-20261117-011514 blank 守卫：策略开启时任一字段空白 → 既有错误码 CAPTCHA_INVALID
        // （前置拦截，不透传 verify）；Bean Validation 仅校验 @Size 上限，不泄露策略语义
        when(systemConfigGateway.findCaptchaPolicy("default")).thenReturn(new CaptchaPolicyView(true));
        stubLoginSuccessPath();
        // 双字段均空白
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", "", " ", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_INVALID.code()));
        // 任一空白同样拒绝
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, " ", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_INVALID.code()));
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", " ", CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_INVALID.code()));
        verify(imageCaptchaUseCase, never()).verify(any(), any());
        verify(authProfileGateway, never()).findByUsername(anyString());
    }

    @Test
    void should_failClosedRequireCaptcha_when_policyQueryFails() {
        // AC-6：策略查询下游异常 fail-closed 视为开启，不因降级放宽安全（WARN 由用例记录）——
        // 携带（无效）验证码按既有错误码拒绝，证明确实前置校验
        when(systemConfigGateway.findCaptchaPolicy("default"))
                .thenThrow(new IllegalStateException("downstream unavailable"));
        stubLoginSuccessPath();
        doThrow(new BizException(AuthError.CAPTCHA_EXPIRED)).when(imageCaptchaUseCase)
                .verify(CAPTCHA_ID, CAPTCHA_CODE);
        assertThatThrownBy(() -> useCase.login("default", "admin", "Correct@123", CAPTCHA_ID, CAPTCHA_CODE, "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.CAPTCHA_EXPIRED.code()));
        verify(imageCaptchaUseCase).verify(CAPTCHA_ID, CAPTCHA_CODE);
        verify(authProfileGateway, never()).findByUsername(anyString());
    }
}
