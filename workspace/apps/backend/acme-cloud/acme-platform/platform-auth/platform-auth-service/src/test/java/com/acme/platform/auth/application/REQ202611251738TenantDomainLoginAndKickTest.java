package com.acme.platform.auth.application;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.api.BatchKickView;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway;
import com.acme.platform.auth.infrastructure.repository.AuthConsumerAccountRepository;
import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.TenantInternalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-20261125-001738 用户名唯一域收敛（platform-auth）用例测试（纯 JUnit + Mockito，编排口径照
 * {@code LoginUseCaseTest} / {@code OnlineSessionUseCaseTest}）：
 *
 * <ul>
 *   <li>AC-2：A/B 各同名 u1，A 域登录按 (A, u1) 域内查档成功且身份归属 A 域；A 域登录不触碰 B 域画像；
 *       B 域同名登录仍按 (B, u1) 解析到 B 域身份。</li>
 *   <li>AC-4：A/B 各有同名在线会话，A 域管理员按名批量踢仅踢 A 域（TenantContext=A）会话，B 域会话与撤销键不受影响。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 消费方签名：契约钉死 {@code AuthProfileClient.findByUsername(tenantCode, username)} 且
 *       「LoginUseCase 传当前登录 tenantCode / auth 消费方同步升级」——本用例按
 *       {@code AuthProfileGateway.findByUsername(tenantCode, username)} 承载；若实现以 TenantContext 隐式承载
 *       保持单参签名，编译红灯交 TL 裁决（不改断言放宽）。</li>
 *   <li>AMB-2 批量踢的租户上下文来源按操作者 {@code TenantContext}（InternalTokenAuthFilter 由 token claim 绑定），
 *       操作者同时绑定 UserContext 角色（沿用 OnlineSessionUseCaseTest 先例）。踢人链路按契约/实现为
 *       {@code findByUserIds} 批量富化 + 会话 tenant_id 域内过滤，按名查档不属于本链路，故不设 findByUsername
 *       的 verify（backend 口径，TL 转达）。</li>
 * </ul>
 */
class REQ202611251738TenantDomainLoginAndKickTest {

    private static final String TENANT_A = "req1738-tenant-a";
    private static final String TENANT_B = "req1738-tenant-b";
    private static final String SHARED_USERNAME = "req1738-u1";
    private static final String PASSWORD = "Correct@1738";

    private final AuthProfileGateway authProfileGateway = mock(AuthProfileGateway.class);
    private final AuthCredentialRepository credentialRepository = mock(AuthCredentialRepository.class);
    private final LoginGuardRedis loginGuard = mock(LoginGuardRedis.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final ImageCaptchaUseCase imageCaptchaUseCase = mock(ImageCaptchaUseCase.class);
    private final LoginLogRecorder loginLogRecorder = mock(LoginLogRecorder.class);
    private final SystemConfigGateway systemConfigGateway = mock(SystemConfigGateway.class);
    private final TenantDirectoryGateway tenantDirectoryGateway = mock(TenantDirectoryGateway.class);
    private final AuthorizationGateway authorizationGateway = mock(AuthorizationGateway.class);
    private final AuthConsumerAccountRepository accountRepository = mock(AuthConsumerAccountRepository.class);

    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10);

    private LoginUseCase loginUseCase;
    private OnlineSessionUseCase onlineSessionUseCase;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret("req1738-login-it-secret-0123456789abcdef");
        properties.setTtlSeconds(1800);
        loginUseCase = new LoginUseCase(authProfileGateway, credentialRepository, loginGuard, sessionRedis,
                sessionRecorder, new UserTokenIssuer(properties), encoder, imageCaptchaUseCase,
                loginLogRecorder, systemConfigGateway, tenantDirectoryGateway);
        onlineSessionUseCase = new OnlineSessionUseCase(authorizationGateway, sessionRecorder, sessionRedis,
                authProfileGateway, accountRepository);

        // 验证码策略关闭（登录成功路径无需验证码，沿用 LoginUseCaseTest should_skipCaptchaVerify 口径）
        when(systemConfigGateway.findCaptchaPolicy(anyString())).thenReturn(new CaptchaPolicyView(false));
        // 租户目录启用且未过期（REQ-20261117-025200 登录第 4.5 步基线）
        when(tenantDirectoryGateway.findByCode(anyString())).thenAnswer(invocation ->
                new TenantInternalView(invocation.getArgument(0), "租户", "ENABLED", null));
        when(sessionRecorder.kickActiveSessions(anyLong())).thenReturn(List.of());
        when(authorizationGateway.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-2：同名用户在 A/B 域分别登录，身份归属各自域，互不干扰 ----------

    @Test
    void should_loginAsTenantAIdentity_when_sameUsernameExistsInTenantAAndB() {
        String hash = encoder.encode(PASSWORD);
        when(authProfileGateway.findByUsername(TENANT_A, SHARED_USERNAME))
                .thenReturn(profile("1001", SHARED_USERNAME, TENANT_A, "operator"));
        when(authProfileGateway.findByUsername(TENANT_B, SHARED_USERNAME))
                .thenReturn(profile("2002", SHARED_USERNAME, TENANT_B, "operator"));
        when(credentialRepository.findByUserId(1001L)).thenReturn(new AuthCredential(1001L, TENANT_A, hash));
        when(credentialRepository.findByUserId(2002L)).thenReturn(new AuthCredential(2002L, TENANT_B, hash));

        AdminTokenIssued issuedA = loginUseCase.login(
                TENANT_A, SHARED_USERNAME, PASSWORD, null, null, "10.0.0.1", "JUnit");

        assertThat(issuedA.userId()).as("A 域 u1 登录身份应归属 A 域用户 1001").isEqualTo(1001L);
        assertThat(issuedA.accessToken()).isNotBlank();
        verify(authProfileGateway).findByUsername(TENANT_A, SHARED_USERNAME);
        verify(authProfileGateway, never()).findByUsername(TENANT_B, SHARED_USERNAME);
        verify(credentialRepository, never()).findByUserId(2002L);

        AdminTokenIssued issuedB = loginUseCase.login(
                TENANT_B, SHARED_USERNAME, PASSWORD, null, null, "10.0.0.1", "JUnit");

        assertThat(issuedB.userId()).as("B 域同名 u1 登录应解析到 B 域用户 2002（B 域不受 A 域登录影响）")
                .isEqualTo(2002L);
        verify(authProfileGateway).findByUsername(TENANT_B, SHARED_USERNAME);
    }

    // ---------- AC-4：按名批量踢仅作用于操作者租户域内会话 ----------

    @Test
    void should_kickOnlyOperatorTenantSessions_when_batchKickByName() {
        TenantContext.bind(new TenantContext(TENANT_A));
        UserContext.bind(new UserContext("1", Set.of("admin")));

        AuthProfileView profileA = profile("301", SHARED_USERNAME, TENANT_A, "operator");
        AuthProfileView profileB = profile("302", SHARED_USERNAME, TENANT_B, "operator");
        when(authProfileGateway.findByUserIds(anyCollection())).thenReturn(Map.of(301L, profileA, 302L, profileB));

        AuthSession a1 = session("req1738-a-1", 301L, TENANT_A);
        AuthSession a2 = session("req1738-a-2", 301L, TENANT_A);
        AuthSession b1 = session("req1738-b-1", 302L, TENANT_B);
        when(sessionRecorder.activeNotExpired()).thenReturn(List.of(a1, a2, b1));

        BatchKickView view = onlineSessionUseCase.kickAllByUsername(SHARED_USERNAME);

        assertThat(view.kickedCount()).as("仅 A 域两条同名会话被踢").isEqualTo(2L);
        assertThat(view.skippedCount()).isZero();
        verify(sessionRecorder).kick(a1);
        verify(sessionRecorder).kick(a2);
        verify(sessionRecorder, never()).kick(b1);
        verify(sessionRedis, times(2)).revoke(anyString(), anyLong());
    }

    // ---------- 支撑 ----------

    private static AuthProfileView profile(String userId, String username, String tenantCode, String... roles) {
        return new AuthProfileView(userId, username, username, tenantCode, "ENABLED", Set.of(roles));
    }

    private static AuthSession session(String tokenId, long userId, String tenantCode) {
        return new AuthSession(tokenId, userId, tenantCode, "B",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800));
    }
}
