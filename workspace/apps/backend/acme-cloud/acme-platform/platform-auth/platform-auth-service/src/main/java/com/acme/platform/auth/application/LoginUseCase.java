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
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.EnableStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 登录用例（FR-001/FR-002，架构设计 4.2 主流程）：
 * 编排不开启整体事务——画像 Feign 查询置于凭据校验前、事务外（M-05；10.2.1），
 * DB 写经 SessionRecorder 独立事务；Redis 异常防爆破降级 fail-open（取舍 #7）。
 */
@Service
public class LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

    private final AuthProfileGateway authProfileGateway;
    private final AuthCredentialRepository credentialRepository;
    private final LoginGuardRedis loginGuard;
    private final SessionRedis sessionRedis;
    private final SessionRecorder sessionRecorder;
    private final UserTokenIssuer tokenIssuer;
    private final BCryptPasswordEncoder passwordEncoder;

    public LoginUseCase(AuthProfileGateway authProfileGateway,
                        AuthCredentialRepository credentialRepository,
                        LoginGuardRedis loginGuard,
                        SessionRedis sessionRedis,
                        SessionRecorder sessionRecorder,
                        UserTokenIssuer tokenIssuer,
                        BCryptPasswordEncoder passwordEncoder) {
        this.authProfileGateway = authProfileGateway;
        this.credentialRepository = credentialRepository;
        this.loginGuard = loginGuard;
        this.sessionRedis = sessionRedis;
        this.sessionRecorder = sessionRecorder;
        this.tokenIssuer = tokenIssuer;
        this.passwordEncoder = passwordEncoder;
    }

    /** B 端账号密码登录（公开端点；单会话互踢 Q-003；统一失败提示 FR-005）。 */
    public AdminTokenIssued login(String username, String password) {
        String normalized = username == null ? "" : username.trim();
        // 1. 锁定预检（Redis fail-open）
        if (loginGuard.isLocked(normalized)) {
            throw new BizException(AuthError.ACCOUNT_LOCKED);
        }
        // 2. 画像查询（事务外远程调用，Q-D06/Q-D08；404 → 统一失败）
        AuthProfileView profile = authProfileGateway.findByUsername(normalized);
        // 3. 账号状态
        if (EnableStatus.DISABLED.name().equals(profile.status())) {
            throw new BizException(AuthError.ACCOUNT_DISABLED);
        }
        Long userId = Long.parseLong(profile.userId());
        // 4. 凭据校验（未设密码与密码错误统一失败，FR-005）
        AuthCredential credential = credentialRepository.findByUserId(userId);
        if (credential == null || !passwordEncoder.matches(password, credential.getPasswordHash())) {
            boolean locked = loginGuard.recordFailure(normalized);
            throw new BizException(locked ? AuthError.ACCOUNT_LOCKED : AuthError.LOGIN_FAILED);
        }
        loginGuard.clearFailures(normalized);
        // 5. 单会话互踢（DB 底账置 KICKED；撤销集写入 best-effort，Q-003）
        kickPreviousSessions(userId);
        // 6. 签发（jti=会话 tokenId；claims 契约见 4.1）+ 会话底账 + 单会话索引
        UserTokenIssuer.IssuedToken issued = tokenIssuer.issue(userId, profile.tenantId(), profile.roles());
        sessionRecorder.record(issued.tokenId(), userId, profile.tenantId(), UserTokenIssuer.CHANNEL_B,
                issued.issuedAt(), issued.expiresAt());
        long ttl = Math.max(1, Duration.between(Instant.now(), issued.expiresAt()).toSeconds());
        sessionRedis.indexSession(userId, issued.tokenId(), ttl);
        return new AdminTokenIssued(issued.token(), "Bearer", issued.expiresAt(), userId,
                profile.username(), profile.nickname());
    }

    /** 互踢：旧活跃会话置 KICKED（事务）→ 撤销集写入（事务外，失败降级 WARN 不阻断登录）。 */
    private void kickPreviousSessions(Long userId) {
        List<SessionRecorder.RevocableToken> previous = sessionRecorder.kickActiveSessions(userId);
        for (SessionRecorder.RevocableToken revocable : previous) {
            long remaining = Duration.between(Instant.now(), revocable.expiresAt()).toSeconds();
            if (remaining <= 0) {
                continue;
            }
            try {
                sessionRedis.revoke(revocable.tokenId(), remaining);
            } catch (RuntimeException ex) {
                log.warn("互踢撤销集写入失败（旧令牌至多存活至过期）：userId={} tokenId={}", userId,
                        revocable.tokenId(), ex);
            }
        }
    }
}
