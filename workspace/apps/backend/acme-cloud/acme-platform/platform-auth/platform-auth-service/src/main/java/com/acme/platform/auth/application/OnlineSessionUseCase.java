package com.acme.platform.auth.application;

import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminOnlineUserView;
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

        List<Long> bUserIds = sessions.stream()
                .filter(session -> UserTokenIssuer.CHANNEL_B.equals(session.getChannel()))
                .map(AuthSession::getUserId)
                .distinct()
                .toList();
        Map<Long, AuthProfileView> profiles = authProfileGateway.findByUserIds(bUserIds);

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

        return sessions.stream().map(session -> toView(session, profiles, phones)).toList();
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

    /** 目标是否超管：B 端按当前画像角色判定（角色变更即时生效）；C 端无角色体系恒否。 */
    private boolean isSuperadmin(AuthSession session) {
        if (!UserTokenIssuer.CHANNEL_B.equals(session.getChannel())) {
            return false;
        }
        AuthProfileView profile = authProfileGateway.findByUserIds(List.of(session.getUserId()))
                .get(session.getUserId());
        return profile != null && profile.roles().contains(SUPERADMIN_ROLE);
    }

    /** 操作者是否超管：网关注入的主体 roles 判定。 */
    private static boolean operatorIsSuperadmin() {
        UserContext operator = UserContext.get();
        return operator != null && operator.roles() != null && operator.roles().contains(SUPERADMIN_ROLE);
    }

    private AdminOnlineUserView toView(AuthSession session, Map<Long, AuthProfileView> profiles,
                                       Map<Long, String> phones) {
        String username = UserTokenIssuer.CHANNEL_B.equals(session.getChannel())
                ? profiles.get(session.getUserId()) == null ? null : profiles.get(session.getUserId()).username()
                : phones.get(session.getUserId());
        SessionRedis.SessionInfo info = sessionRedis.readSessionInfo(session.getTokenId()).orElse(null);
        Instant lastActiveAt = info == null || info.lastActiveAt() == null
                ? session.getIssuedAt() : info.lastActiveAt();
        return new AdminOnlineUserView(session.getTokenId(), username,
                info == null ? null : info.ip(), session.getIssuedAt(), lastActiveAt, session.getChannel());
    }
}
