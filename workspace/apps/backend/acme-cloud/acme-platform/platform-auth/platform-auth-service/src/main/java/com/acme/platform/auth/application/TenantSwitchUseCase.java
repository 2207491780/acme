package com.acme.platform.auth.application;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.TenantInternalView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 超管跨租户切换用例（REQ-20261117-025200 确认记录④，认证端点 /admin/v1/auth/tenant-switch）：
 * 前置校验「当前用户为内置 super_admin」——以 UserContext roles 为查询键，经 system 内部鉴权面
 * 实查内置角色（roles 不作信任锚、不编码短路）；非超管 403（TENANT_SWITCH_FORBIDDEN）。
 * 目标租户必须 ENABLED 且未过期（复用 by-code 契约，确认记录③；未知/停用/过期统一
 * TENANT_SWITCH_FAILED）。签发 = 旧会话互踢（kickActiveSessions）→ issue(userId, targetTenantCode,
 * Set.of("super_admin"))（内置角色跨租户回退使 super_admin 在目标租户全权）→ 会话底账 + Redis
 * 单会话索引 + session info（照抄 LoginUseCase 第 7-9 步口径，含登录日志记录 channel B）。
 */
@Service
public class TenantSwitchUseCase {

    private static final Logger log = LoggerFactory.getLogger(TenantSwitchUseCase.class);

    /** 内置超管角色编码（平台保留编码，与 system 侧 PermissionDecisionService 保留码同口径）。 */
    static final String SUPER_ADMIN_ROLE = "super_admin";

    private final AuthorizationGateway authorizationGateway;
    private final TenantDirectoryGateway tenantDirectoryGateway;
    private final AuthProfileGateway authProfileGateway;
    private final SessionRecorder sessionRecorder;
    private final SessionRedis sessionRedis;
    private final UserTokenIssuer tokenIssuer;
    private final LoginLogRecorder loginLogRecorder;

    public TenantSwitchUseCase(AuthorizationGateway authorizationGateway,
                               TenantDirectoryGateway tenantDirectoryGateway,
                               AuthProfileGateway authProfileGateway,
                               SessionRecorder sessionRecorder,
                               SessionRedis sessionRedis,
                               UserTokenIssuer tokenIssuer,
                               LoginLogRecorder loginLogRecorder) {
        this.authorizationGateway = authorizationGateway;
        this.tenantDirectoryGateway = tenantDirectoryGateway;
        this.authProfileGateway = authProfileGateway;
        this.sessionRecorder = sessionRecorder;
        this.sessionRedis = sessionRedis;
        this.tokenIssuer = tokenIssuer;
        this.loginLogRecorder = loginLogRecorder;
    }

    /**
     * 跨租户切换并签发目标租户 B 端访问令牌（当前会话互踢，Q-003 单会话语义延续）。
     *
     * @param targetTenantCode 目标租户代码（调用方已 trim；空白由接口层 400 拒绝）
     * @param clientIp         客户端 IP（登录日志 / 会话信息富化）
     * @param userAgent        客户端 UA（登录日志）
     * @return 目标租户令牌签发结果（wire userId 为 string，同 login）
     */
    public AdminTokenIssued switchTenant(String targetTenantCode, String clientIp, String userAgent) {
        // 1. 前置：当前主体经 system 内部鉴权面实查内置角色（roles 仅查询键，内置角色行权威）
        UserContext current = UserContext.get();
        if (current == null || current.userId() == null || current.userId().isBlank()
                || !isBuiltInSuperAdmin(current.roles())) {
            throw new BizException(AuthError.TENANT_SWITCH_FORBIDDEN);
        }
        Long userId = Long.parseLong(current.userId());
        // 2. 目标租户校验（ENABLED 且未过期；未知/停用/过期/下游不可达统一失败，fail-closed）
        TenantInternalView target = requireSwitchableTarget(targetTenantCode);
        // 3. 旧会话互踢（底账置 KICKED 事务；撤销集事务外 best-effort，Q-003 同登录口径）
        kickPreviousSessions(userId);
        // 4. 签发（roles claim = 内置 super_admin，跨租户回退全权）+ 会话底账 + 单会话索引 + 会话信息
        UserTokenIssuer.IssuedToken issued = tokenIssuer.issue(userId, target.tenantCode(),
                Set.of(SUPER_ADMIN_ROLE));
        sessionRecorder.record(issued.tokenId(), userId, target.tenantCode(), UserTokenIssuer.CHANNEL_B,
                issued.issuedAt(), issued.expiresAt());
        long ttl = Math.max(1, Duration.between(Instant.now(), issued.expiresAt()).toSeconds());
        sessionRedis.indexSession(userId, issued.tokenId(), ttl);
        sessionRedis.writeSessionInfo(issued.tokenId(), clientIp, issued.issuedAt(), ttl);
        // 5. 登录日志（channel B 成功；口径同 LoginUseCase 第 9 步）
        AuthProfileView profile = resolveProfile(userId);
        loginLogRecorder.record(LoginLogRecorder.Entry.capture(profile.username(), UserTokenIssuer.CHANNEL_B,
                clientIp, userAgent, true, null));
        return new AdminTokenIssued(issued.token(), "Bearer", issued.expiresAt(), userId,
                profile.username(), profile.nickname());
    }

    /**
     * 内置超管判定（实查内置角色行；查询不可达按空集 fail-closed，WARN 记录）。
     */
    private boolean isBuiltInSuperAdmin(Set<String> roles) {
        try {
            return authorizationGateway.currentBuiltinRoles().contains(SUPER_ADMIN_ROLE);
        } catch (RuntimeException ex) {
            log.warn("内置角色实查失败，切换前置 fail-closed 拒绝", ex);
            return false;
        }
    }

    /**
     * 目标租户可切换性校验（ENABLED 且未过期；未知/停用/过期/下游不可达统一
     * TENANT_SWITCH_FAILED，不泄露目标租户存在性差异——与 FR-005 统一失败口径同型）。
     */
    private TenantInternalView requireSwitchableTarget(String targetTenantCode) {
        TenantInternalView target;
        try {
            target = tenantDirectoryGateway.findByCode(targetTenantCode);
        } catch (RuntimeException ex) {
            // 未知租户（TENANT_NOT_FOUND 错误信封）与下游不可达统一按目标不可用处理
            log.info("切换目标租户校验未通过（未知或下游不可达）：targetTenantCode={}", targetTenantCode);
            throw new BizException(AuthError.TENANT_SWITCH_FAILED);
        }
        Instant now = Instant.now();
        if (target == null
                || !EnableStatus.ENABLED.name().equals(target.status())
                || (target.expiresAt() != null && target.expiresAt().isBefore(now))) {
            throw new BizException(AuthError.TENANT_SWITCH_FAILED);
        }
        return target;
    }

    /** 展示主体（username/nickname 富化，复用既有批量画像查询；缺失按空串兜底不阻断签发）。 */
    private AuthProfileView resolveProfile(Long userId) {
        Map<Long, AuthProfileView> profiles = authProfileGateway.findByUserIds(List.of(userId));
        AuthProfileView profile = profiles.get(userId);
        if (profile == null) {
            log.warn("切换主体画像缺失（展示字段缺省）：userId={}", userId);
            return new AuthProfileView(String.valueOf(userId), "", null, null,
                    EnableStatus.ENABLED.name(), Set.of());
        }
        return profile;
    }

    /** 互踢：旧活跃会话置 KICKED（事务）→ 撤销集写入（事务外，失败降级 WARN 不阻断切换）。 */
    private void kickPreviousSessions(Long userId) {
        List<SessionRecorder.RevocableToken> previous = sessionRecorder.kickActiveSessions(userId);
        SessionRevocations.bestEffort(sessionRedis, previous, userId, "切换互踢");
    }
}
