package com.acme.platform.auth.application;

import com.acme.common.core.BizException;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.SelfSessionView;
import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 自助账号安全用例（REQ-20261119-082531 确认记录③④，全部免权限点仅本人）：
 * 改密=旧密码 BCrypt 校验（失败 OLD_PASSWORD_INVALID，密码未变）→ 新密码 8-64 策略
 * （PASSWORD_POLICY_VIOLATED）→ 新旧相同拒绝（SAME_PASSWORD）→ upsert 凭据 +
 * 其他活跃会话全部置 KICKED 且撤销集写入（当前会话保留——底账 ACTIVE 且不进撤销集，
 * 改密响应后当前登录态保持，确认记录③「响应头不变」语义）；
 * 会话=本人 ACTIVE 未过期集合富化（ip/lastActiveAt 取会话信息哈希，缺失回退签发时间）＋
 * 当前标记（Authorization Bearer jti 匹配）；单会话下线仅本人会话可达
 * （他人会话按 NOT_FOUND 语义防探测，确认记录④）；下线其他设备保留当前会话。
 * 身份来源：userId 取 UserContext（InternalTokenAuthFilter 绑定，网关验签事实）；
 * 当前 tokenId 取 Authorization Bearer 用户令牌自校验（网关原样透传该头，
 * LogoutUseCase/AdminRenewUseCase 同款先例——网关注入的内部令牌 X-Acme-Token 的 jti
 * 是逐请求新签值，与会话 tokenId 无关，故不可作为当前会话标识）。
 * Redis 撤销写失败显式失败响应（CommonError.INTERNAL_ERROR，FR-004——不得「成功但令牌仍可用」）。
 */
@Service
public class SelfAccountUseCase {

    /** 新密码长度策略（与 ResetPasswordUseCase 管理员面同口径：8-64）。 */
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 64;

    private final AuthCredentialRepository credentialRepository;
    private final CredentialRecorder credentialRecorder;
    private final SessionRecorder sessionRecorder;
    private final SessionRedis sessionRedis;
    private final UserTokenIssuer tokenIssuer;
    private final BCryptPasswordEncoder passwordEncoder;

    public SelfAccountUseCase(AuthCredentialRepository credentialRepository,
                              CredentialRecorder credentialRecorder,
                              SessionRecorder sessionRecorder,
                              SessionRedis sessionRedis,
                              UserTokenIssuer tokenIssuer,
                              BCryptPasswordEncoder passwordEncoder) {
        this.credentialRepository = credentialRepository;
        this.credentialRecorder = credentialRecorder;
        this.sessionRecorder = sessionRecorder;
        this.sessionRedis = sessionRedis;
        this.tokenIssuer = tokenIssuer;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 自助改密（确认记录③）：校验链=旧密码匹配 → 新密码策略 → 新旧不同；随后 upsert 凭据并
     * 踢其他活跃会话（底账 KICKED＋撤销集写入），当前会话保留（令牌与底账均不变）。
     *
     * @param userId         当前主体（UserContext.userId，网关验签事实）
     * @param bearerToken    当前请求所持用户访问令牌（Authorization Bearer 原文，含 Bearer 前缀剥离后值）
     * @param oldPassword    旧密码（明文，BCrypt matches）
     * @param newPassword    新密码（明文，8-64 策略）
     */
    public void changePassword(Long userId, String bearerToken, String oldPassword, String newPassword) {
        String currentTokenId = currentTokenId(bearerToken);
        AuthCredential credential = credentialRepository.findByUserId(userId);
        if (credential == null || !passwordEncoder.matches(oldPassword, credential.getPasswordHash())) {
            // 旧密码校验失败（含未设凭据——不泄露凭据存在性，统一同一错误码）
            throw new BizException(AuthError.OLD_PASSWORD_INVALID);
        }
        if (newPassword == null || newPassword.length() < PASSWORD_MIN || newPassword.length() > PASSWORD_MAX) {
            throw new BizException(AuthError.PASSWORD_POLICY_VIOLATED);
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(AuthError.SAME_PASSWORD);
        }
        String hash = passwordEncoder.encode(newPassword);
        // 事务内仅本服务库写（M-05）；待撤销清单随事务返回、Redis 撤销写在事务外（10.2.1）
        List<SessionRecorder.RevocableToken> revocables =
                credentialRecorder.resetCredential(userId, hash, ResetPasswordUseCase.FALLBACK_TENANT,
                        currentTokenId);
        revokeAll(revocables);
    }

    /**
     * 本人在线会话列表（确认记录④）：仅本人 ACTIVE 未过期会话（过期会话不出现，
     * 与在线用户列表 activeNotExpired 同口径）；ip/lastActiveAt 取会话信息哈希
     * （缺失/脏值回退 issuedAt——展示面 fail-open，与 OnlineSessionUseCase 同口径）；
     * current=当前令牌 jti 匹配标记。
     */
    public List<SelfSessionView> listMySessions(Long userId, String bearerToken) {
        String currentTokenId = currentTokenId(bearerToken);
        List<AuthSession> sessions = sessionRecorder.activeByUserId(userId);
        List<SelfSessionView> views = new ArrayList<>(sessions.size());
        for (AuthSession session : sessions) {
            SessionRedis.SessionInfo info = sessionRedis.readSessionInfo(session.getTokenId()).orElse(null);
            Instant lastActiveAt = info == null || info.lastActiveAt() == null
                    ? session.getIssuedAt() : info.lastActiveAt();
            views.add(new SelfSessionView(session.getTokenId(),
                    info == null ? null : info.ip(),
                    session.getIssuedAt(), lastActiveAt,
                    session.getTokenId().equals(currentTokenId)));
        }
        return views;
    }

    /**
     * 下线本人指定会话（确认记录④）：目标可踢（存在/非终态/未过期）且属本人——
     * 非本人/不存在统一 SESSION_NOT_FOUND（NOT_FOUND 语义防探测，不泄露他人会话存在性）；
     * 底账置 KICKED + 撤销集写入（写失败显式失败）。
     */
    public void kickMySession(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(AuthError.SESSION_NOT_FOUND);
        }
        AuthSession session = sessionRecorder.kickableByTokenId(sessionId.trim())
                .orElseThrow(() -> new BizException(AuthError.SESSION_NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            // 非本人会话：与不存在同语义（防探测，确认记录④）
            throw new BizException(AuthError.SESSION_NOT_FOUND);
        }
        sessionRecorder.kick(session);
        revokeAll(List.of(new SessionRecorder.RevocableToken(session.getTokenId(), session.getExpiresAt())));
    }

    /**
     * 下线本人其他全部活跃会话（确认记录④）：保留当前会话（底账 ACTIVE 且不撤销），其余逐个
     * 底账 KICKED + 撤销集写入；无其他活跃会话时幂等成功（空清单语义）。
     */
    public void kickMyOtherSessions(Long userId, String bearerToken) {
        String currentTokenId = currentTokenId(bearerToken);
        List<SessionRecorder.RevocableToken> revocables =
                sessionRecorder.kickActiveSessionsExcept(userId, currentTokenId);
        revokeAll(revocables);
    }

    /**
     * 撤销集批量写入（FR-004）：逐个按剩余 TTL 写入；剩余有效期不足 1s 的自然过期跳过；
     * 任一写失败 → 显式失败响应（底账已终态而撤销失败，不得「成功但令牌仍可用」）。
     */
    private void revokeAll(List<SessionRecorder.RevocableToken> revocables) {
        SessionRevocations.allOrFail(sessionRedis, revocables);
    }

    /**
     * 当前会话标识解析（Authorization Bearer 用户令牌自校验取 jti）：
     * 令牌缺失按未认证拒绝（SecurityError.UNAUTHORIZED——无当前会话概念，显式 401 而非
     * 交由 JWT 解析报内部错误）；无效/过期由 {@link UserTokenIssuer#verify} 统一抛 SecurityError。
     */
    private String currentTokenId(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new BizException(com.acme.framework.security.support.SecurityError.UNAUTHORIZED);
        }
        return tokenIssuer.verify(bearerToken).tokenId();
    }
}
