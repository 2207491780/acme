package com.acme.platform.auth.application;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.TenantInternalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超管跨租户切换用例测试（REQ-20261117-025200 确认记录④，纯 JUnit 无 Spring）：
 * AC-7（非超管 403 拒绝且不产生新会话）、目标租户不可用三态统一 TENANT_SWITCH_FAILED、
 * AC-6（成功签发互踢：新令牌 tenantId=目标、底账/索引/会话信息/登录日志 channel B 全链路）。
 */
class TenantSwitchUseCaseTest {

    private static final String SECRET = "tenant-switch-test-secret-0123456789abcdef";

    private final AuthorizationGateway authorizationGateway = mock(AuthorizationGateway.class);
    private final TenantDirectoryGateway tenantDirectoryGateway = mock(TenantDirectoryGateway.class);
    private final AuthProfileGateway authProfileGateway = mock(AuthProfileGateway.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final LoginLogRecorder loginLogRecorder = mock(LoginLogRecorder.class);

    private TenantSwitchUseCase useCase;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setTtlSeconds(1800);
        useCase = new TenantSwitchUseCase(authorizationGateway, tenantDirectoryGateway, authProfileGateway,
                sessionRecorder, sessionRedis, new UserTokenIssuer(properties), loginLogRecorder);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private void bindOperator(String userId, Set<String> roles) {
        UserContext.bind(new UserContext(userId, roles));
    }

    private void stubTargetEnabled() {
        when(tenantDirectoryGateway.findByCode("target")).thenReturn(
                new TenantInternalView("target", "目标租户", EnableStatus.ENABLED.name(), null));
    }

    private void stubProfile() {
        when(authProfileGateway.findByUserIds(any())).thenReturn(Map.of(
                1001L, new AuthProfileView("1001", "admin", "超级管理员", "default", "ENABLED",
                        Set.of("super_admin"))));
    }

    // ---------- AC-7：非超管 → 403 拒绝且不产生新会话 ----------

    @Test
    void should_rejectWithForbiddenAndNoSession_when_operatorLacksBuiltInSuperAdmin() {
        // AC-7：内置角色实查不含 super_admin（roles 仅查询键、内置角色行权威）→ 403 权限语义
        bindOperator("2002", Set.of("tenant_admin"));
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of());

        assertThatThrownBy(() -> useCase.switchTenant("target", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    assertThat(((BizException) ex).errorCode().code())
                            .isEqualTo(AuthError.TENANT_SWITCH_FORBIDDEN.code());
                    assertThat(((com.acme.framework.core.support.HttpStatusAware) ((BizException) ex).errorCode())
                            .httpStatus()).as("非超管 403 权限语义").isEqualTo(403);
                });
        // 拒绝路径不触达目标校验与会话面
        verify(tenantDirectoryGateway, never()).findByCode(anyString());
        verify(sessionRecorder, never()).kickActiveSessions(anyLong());
        verify(sessionRecorder, never()).record(anyString(), anyLong(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void should_rejectWithForbidden_when_builtinRoleQueryFails() {
        // 实查不可达 fail-closed 拒绝（内置角色判定不降级放宽）
        bindOperator("1001", Set.of("super_admin"));
        when(authorizationGateway.currentBuiltinRoles()).thenThrow(new IllegalStateException("downstream"));

        assertThatThrownBy(() -> useCase.switchTenant("target", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.TENANT_SWITCH_FORBIDDEN.code()));
        verify(sessionRecorder, never()).kickActiveSessions(anyLong());
    }

    // ---------- 目标租户不可用三态：统一 TENANT_SWITCH_FAILED ----------

    @Test
    void should_rejectWithSwitchFailed_when_targetTenantDisabled() {
        bindOperator("1001", Set.of("super_admin"));
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        when(tenantDirectoryGateway.findByCode("target")).thenReturn(
                new TenantInternalView("target", "目标租户", EnableStatus.DISABLED.name(), null));

        assertThatThrownBy(() -> useCase.switchTenant("target", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.TENANT_SWITCH_FAILED.code()));
        verify(sessionRecorder, never()).kickActiveSessions(anyLong());
    }

    @Test
    void should_rejectWithSwitchFailed_when_targetTenantExpired() {
        bindOperator("1001", Set.of("super_admin"));
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        when(tenantDirectoryGateway.findByCode("target")).thenReturn(
                new TenantInternalView("target", "目标租户", EnableStatus.ENABLED.name(),
                        Instant.now().minusSeconds(3600)));

        assertThatThrownBy(() -> useCase.switchTenant("target", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.TENANT_SWITCH_FAILED.code()));
        verify(sessionRecorder, never()).kickActiveSessions(anyLong());
    }

    @Test
    void should_rejectWithSwitchFailed_when_targetTenantUnknown() {
        // 未知租户（404 错误信封解码为 BizException）与停用/过期统一口径
        bindOperator("1001", Set.of("super_admin"));
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        when(tenantDirectoryGateway.findByCode("target")).thenThrow(new IllegalStateException("tenant not found"));

        assertThatThrownBy(() -> useCase.switchTenant("target", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.TENANT_SWITCH_FAILED.code()));
    }

    // ---------- AC-6：成功签发互踢 ----------

    @Test
    void should_issueTargetTenantTokenAndKickOldSessions_when_superAdminSwitches() {
        // AC-6：超管切换 → 新令牌（claims tenantId=目标）、旧会话互踢 KICKED＋撤销集、底账/索引/会话信息
        bindOperator("1001", Set.of("super_admin"));
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        stubTargetEnabled();
        stubProfile();
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of(
                new SessionRecorder.RevocableToken("old-jti", Instant.now().plusSeconds(600))));

        AdminTokenIssued issued = useCase.switchTenant("target", "10.0.0.1", "JUnit");

        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.userId()).isEqualTo(1001L);
        assertThat(issued.username()).isEqualTo("admin");
        // claims 校验：tenantId=目标租户、roles=内置超管（跨租户回退全权）
        UserTokenIssuer issuer = new UserTokenIssuer(authProperties());
        var access = issuer.verifyAccess(issued.accessToken());
        assertThat(access.tenantId()).isEqualTo("target");
        assertThat(access.roles()).containsExactly("super_admin");
        // 互踢与撤销集
        verify(sessionRecorder).kickActiveSessions(1001L);
        verify(sessionRedis).revoke(eq("old-jti"), anyLong());
        // 新会话底账 + 单会话索引 + 会话信息（照抄 LoginUseCase 第 7-9 步口径）
        verify(sessionRecorder).record(anyString(), eq(1001L), eq("target"), eq("B"),
                any(Instant.class), any(Instant.class));
        verify(sessionRedis).indexSession(eq(1001L), anyString(), anyLong());
        verify(sessionRedis).writeSessionInfo(anyString(), eq("10.0.0.1"), any(Instant.class), anyLong());
        // 登录日志（channel B 成功）
        verify(loginLogRecorder).record(any(LoginLogRecorder.Entry.class));
    }

    @Test
    void should_issueTokenEvenWhenOldRevokeWriteFails_when_superAdminSwitches() {
        // 互踢撤销集写入失败降级 WARN 不阻断切换（同登录口径）
        bindOperator("1001", Set.of("super_admin"));
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        stubTargetEnabled();
        stubProfile();
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of(
                new SessionRecorder.RevocableToken("old-jti", Instant.now().plusSeconds(600))));
        doSessionRedisRevokeFailure();

        assertThat(useCase.switchTenant("target", "10.0.0.1", "JUnit").accessToken()).isNotBlank();
        verify(sessionRecorder).record(anyString(), eq(1001L), eq("target"), eq("B"),
                any(Instant.class), any(Instant.class));
    }

    private void doSessionRedisRevokeFailure() {
        org.mockito.Mockito.doThrow(new SessionRedis.RedisKeyWriteFailedException("old-jti", null))
                .when(sessionRedis).revoke(eq("old-jti"), anyLong());
    }

    private AuthTokenProperties authProperties() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setTtlSeconds(1800);
        return properties;
    }
}
