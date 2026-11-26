package com.acme.platform.auth.application;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminOnlineUserView;
import com.acme.platform.auth.api.BatchKickView;
import com.acme.platform.auth.domain.AuthConsumerAccount;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.infrastructure.repository.AuthConsumerAccountRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.SystemPermissions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线用户管理用例（REQ-20261114-120105 AC-3/AC-5）：
 * 列表=AuthSession 活跃且未过期集合（username 经 system 画像批量解析 / C 端手机号本地解析；
 * ip/lastActiveAt 取登录/续约/刷新时写入的会话信息，缺失按 null/回退展示）；
 * 踢下线=底账置 KICKED + 撤销集写入（立即生效）；目标超管保护（非超管拒绝，超管互踢允许）。
 * 权限点 auth:online:list / auth:online:revoke（经 system 判定，越权 403 PERMISSION_DENIED）。
 * REQ-20261117-061017 确认⑥：批量下线（枚举该用户全部 ACTIVE 未过期会话逐个 kick，
 * 超管会话跳过并计数；未知 username 空集语义 0/0）。
 * REQ-20261125-001738 确认③：按名批量踢收敛为操作者租户域内——仅匹配 TenantContext 指向的
 * 租户会话（会话底账 tenant_id 存租户代码），跨租户同名会话不受影响；超管跨租户运维须先
 * 经租户切换取得目标上下文。
 */
@Service
public class OnlineSessionUseCase {

    /** 内置超管角色编码（platform-system V2 种子 role_code='super_admin'，built_in 保护）。 */
    static final String SUPERADMIN_ROLE = "super_admin";

    private final AuthorizationGateway authorizationGateway;
    private final SessionRecorder sessionRecorder;
    private final SessionRedis sessionRedis;
    private final AuthProfileGateway authProfileGateway;
    private final AuthConsumerAccountRepository accountRepository;

    public OnlineSessionUseCase(AuthorizationGateway authorizationGateway,
                                SessionRecorder sessionRecorder,
                                SessionRedis sessionRedis,
                                AuthProfileGateway authProfileGateway,
                                AuthConsumerAccountRepository accountRepository) {
        this.authorizationGateway = authorizationGateway;
        this.sessionRecorder = sessionRecorder;
        this.sessionRedis = sessionRedis;
        this.authProfileGateway = authProfileGateway;
        this.accountRepository = accountRepository;
    }

    /** 活跃会话列表（过期会话不出现，AC-3）。 */
    public List<AdminOnlineUserView> listActive() {
        authorizationGateway.requirePermission(SystemPermissions.AUTH_ONLINE_LIST);
        List<AuthSession> sessions = sessionRecorder.activeNotExpired();
        Map<Long, AuthProfileView> profiles = bProfiles(sessions);
        Map<Long, String> phones = cPhones(sessions);
        return sessions.stream().map(session -> toView(session, profiles, phones)).toList();
    }

    /**
     * 活跃会话计数（REQ-20261119-011524 确认记录②，GET /internal/v1/online-count 数据源）：
     * 免权限点内部统计口径（服务身份调用，Q-D08）——与 listActive 同口径的 SQL 计数（ACTIVE 且未过期），
     * 不过滤租户（Q-D04），不暴露会话明细，无越权面。
     */
    public long countActive() {
        return sessionRecorder.countActive();
    }

    /**
     * 踢下线（AC-4/AC-5）：目标可踢（存在 / 非终态 / 未过期，否则 SESSION_NOT_FOUND）→
     * 超管保护判定（目标超管且操作者非超管 = REVOKE_SUPERADMIN_FORBIDDEN；超管互踢允许）→
     * 底账置 KICKED → 撤销集写入（剩余 TTL；写失败显式失败响应，不得「成功但令牌仍可用」，FR-004 同口径）。
     *
     * @param sessionId 目标会话 id（token jti）
     * @return 被踢会话 id（确认反馈用）
     */
    public String kick(String sessionId) {
        authorizationGateway.requirePermission(SystemPermissions.AUTH_ONLINE_REVOKE);
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(AuthError.SESSION_NOT_FOUND);
        }
        AuthSession session = sessionRecorder.kickableByTokenId(sessionId.trim())
                .orElseThrow(() -> new BizException(AuthError.SESSION_NOT_FOUND));
        return kickActive(session);
    }

    /**
     * 批量下线（REQ-20261117-061017 确认⑥ + REQ-20261125-001738 确认③）：在当前操作者租户域内
     * 按 username 枚举 ACTIVE 未过期会话逐个 kick（复用 kick 语义含超管保护）；超管会话跳过并计数
     * skipped（操作者非超管时）；未知 username / 租户上下文缺失均空集语义（kicked=0/skipped=0，
     * 不报 USER_NOT_FOUND）；竞态消失的会话（kick 时已过期/终态）计入 skipped，不阻断批内其余会话。
     *
     * @param username 目标用户名（B 端用户名 / C 端手机号；空白会话无匹配 → 空集语义）
     * @return 被踢/跳过计数
     */
    public BatchKickView kickAllByUsername(String username) {
        authorizationGateway.requirePermission(SystemPermissions.AUTH_ONLINE_REVOKE);
        TenantContext operatorTenant = TenantContext.get();
        String tenantId = operatorTenant == null ? null : operatorTenant.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // 域内踢的租户来源缺失：fail-closed 空集，不得退化为跨租户全局踢
            return new BatchKickView(0, 0);
        }
        String tenantCode = tenantId.trim();
        List<AuthSession> sessions = sessionRecorder.activeNotExpired().stream()
                .filter(session -> tenantCode.equals(session.getTenantId()))
                .toList();
        Map<Long, AuthProfileView> profiles = bProfiles(sessions);
        Map<Long, String> phones = cPhones(sessions);
        String target = username == null ? "" : username.trim();
        List<AuthSession> matched = sessions.stream()
                .filter(session -> target.equals(usernameOf(session, profiles, phones)))
                .toList();
        boolean operatorSuperadmin = operatorIsSuperadmin();
        long kicked = 0;
        long skipped = 0;
        for (AuthSession session : matched) {
            if (isSuperadminProfile(profiles.get(session.getUserId())) && !operatorSuperadmin) {
                skipped++;
                continue;
            }
            try {
                kickActive(session);
                kicked++;
            } catch (BizException ex) {
                if (AuthError.SESSION_NOT_FOUND == ex.errorCode()) {
                    // 竞态消失（枚举后被踢/过期）：跳过计数，不阻断批内其余会话
                    skipped++;
                    continue;
                }
                throw ex;
            }
        }
        return new BatchKickView(kicked, skipped);
    }

    /**
     * 单会话踢下线内核（权限判定在调用方）：超管保护 → 底账置 KICKED → 撤销集写入
     * （写失败显式失败，不得「成功但令牌仍可用」）。
     */
    private String kickActive(AuthSession session) {
        if (isSuperadmin(session) && !operatorIsSuperadmin()) {
            throw new BizException(AuthError.REVOKE_SUPERADMIN_FORBIDDEN);
        }
        long remaining = Math.max(1, Duration.between(Instant.now(), session.getExpiresAt()).toSeconds());
        sessionRecorder.kick(session);
        try {
            sessionRedis.revoke(session.getTokenId(), remaining);
        } catch (RuntimeException ex) {
            // 底账已 KICKED 而撤销失败：显式失败响应（FR-004 异常分支，与重置密码同口径）
            throw new BizException(CommonError.INTERNAL_ERROR);
        }
        return session.getTokenId();
    }

    /** B 端画像批量解析（列表/批量下线共用的 username 富化数据源）。 */
    private Map<Long, AuthProfileView> bProfiles(List<AuthSession> sessions) {
        List<Long> bUserIds = sessions.stream()
                .filter(session -> UserTokenIssuer.CHANNEL_B.equals(session.getChannel()))
                .map(AuthSession::getUserId)
                .distinct()
                .toList();
        return authProfileGateway.findByUserIds(bUserIds);
    }

    /** C 端手机号本地解析（列表/批量下线共用的 username 富化数据源）。 */
    private Map<Long, String> cPhones(List<AuthSession> sessions) {
        List<Long> cUserIds = sessions.stream()
                .filter(session -> UserTokenIssuer.CHANNEL_C.equals(session.getChannel()))
                .map(AuthSession::getUserId)
                .distinct()
                .toList();
        Map<Long, String> phones = new HashMap<>();
        if (!cUserIds.isEmpty()) {
            for (AuthConsumerAccount account : accountRepository.findAllById(cUserIds)) {
                phones.put(account.getId(), account.getPhone());
            }
        }
        return phones;
    }

    /** username 解析（B 端画像 username / C 端手机号；画像缺失为 null）。 */
    private static String usernameOf(AuthSession session, Map<Long, AuthProfileView> profiles,
                                     Map<Long, String> phones) {
        return UserTokenIssuer.CHANNEL_B.equals(session.getChannel())
                ? profiles.get(session.getUserId()) == null
                ? null : profiles.get(session.getUserId()).username()
                : phones.get(session.getUserId());
    }

    /** 目标是否超管：B 端按当前画像角色判定（角色变更即时生效）；C 端无角色体系恒否。 */
    private boolean isSuperadmin(AuthSession session) {
        if (!UserTokenIssuer.CHANNEL_B.equals(session.getChannel())) {
            return false;
        }
        return isSuperadminProfile(authProfileGateway.findByUserIds(List.of(session.getUserId()))
                .get(session.getUserId()));
    }

    /** 画像是否含内置超管角色（批量判定共用）。 */
    private static boolean isSuperadminProfile(AuthProfileView profile) {
        return profile != null && profile.roles().contains(SUPERADMIN_ROLE);
    }

    /** 操作者是否超管：网关注入的主体 roles 判定。 */
    private static boolean operatorIsSuperadmin() {
        UserContext operator = UserContext.get();
        return operator != null && operator.roles() != null && operator.roles().contains(SUPERADMIN_ROLE);
    }

    private AdminOnlineUserView toView(AuthSession session, Map<Long, AuthProfileView> profiles,
                                       Map<Long, String> phones) {
        SessionRedis.SessionInfo info = sessionRedis.readSessionInfo(session.getTokenId()).orElse(null);
        Instant lastActiveAt = info == null || info.lastActiveAt() == null
                ? session.getIssuedAt() : info.lastActiveAt();
        return new AdminOnlineUserView(session.getTokenId(),
                usernameOf(session, profiles, phones),
                info == null ? null : info.ip(), session.getIssuedAt(), lastActiveAt, session.getChannel());
    }
}
