package com.acme.platform.auth.application;

import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.domain.SessionStatus;
import com.acme.platform.auth.infrastructure.repository.AuthSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话底账记录器（架构设计 4.4）：DB 写收敛事务（10.2.1 仅覆盖本服务库；事务内无远程调用 M-05——
 * Redis 撤销 / 索引写入由用例在事务外执行）。
 */
@Component
public class SessionRecorder {

    /** 待撤销令牌（tokenId + 过期时间，剩余有效期即撤销集 TTL）。 */
    public record RevocableToken(String tokenId, Instant expiresAt) {
    }

    private final AuthSessionRepository sessionRepository;

    public SessionRecorder(AuthSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** 记录新会话（ACTIVE）。 */
    @Transactional
    public void record(String tokenId, Long userId, String tenantId, String channel,
                       Instant issuedAt, Instant expiresAt) {
        sessionRepository.save(new AuthSession(tokenId, userId, tenantId, channel, issuedAt, expiresAt));
    }

    /** 记录 C 端双令牌会话（consumer-auth-refresh-dataperm）。 */
    @Transactional
    public void recordConsumer(String tokenId, String refreshTokenId, Long userId, String tenantId,
                               Instant issuedAt, Instant accessExpiresAt, Instant refreshExpiresAt) {
        AuthSession session = new AuthSession(tokenId, userId, tenantId, "C", issuedAt, refreshExpiresAt);
        session.bindRefreshToken(refreshTokenId);
        sessionRepository.save(session);
        // 会话行以刷新令牌 TTL 为底账生命周期（长于 access；expiresAt 取二者较大值）
    }

    /** 会话关联的刷新令牌 jti（C 端登出同撤；缺失为空）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<String> refreshTokenOf(String tokenId) {
        return sessionRepository.findByTokenId(tokenId)
                .map(AuthSession::getRefreshTokenId);
    }

    /**
     * 按刷新令牌 jti 定位活跃会话（刷新链路取签发主体，consumer-auth-refresh-dataperm）。
     *
     * @return 活跃会话；缺失或终态为空
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AuthSession> activeByRefreshToken(String refreshTokenId) {
        return sessionRepository.findByRefreshTokenId(refreshTokenId)
                .filter(session -> !session.isTerminal());
    }

    /**
     * 刷新轮换（consumer-auth-refresh-dataperm）：按旧刷新令牌 jti 定位会话并换代双 jti。
     *
     * @return 轮换后的会话；未找到（含终态）返回 null——调用方按无效刷新令牌拒绝
     */
    @Transactional
    public AuthSession rotateByRefreshToken(String refreshTokenId, String newTokenId,
                                            String newRefreshTokenId, Instant refreshExpiresAt) {
        return sessionRepository.findByRefreshTokenId(refreshTokenId)
                .filter(session -> !session.isTerminal())
                .map(session -> {
                    session.rotate(newTokenId, newRefreshTokenId, refreshExpiresAt);
                    return sessionRepository.save(session);
                })
                .orElse(null);
    }

    /** 该用户全部活跃会话置 KICKED，返回待撤销清单（互踢 / 重置密码共用）。 */
    @Transactional
    public List<RevocableToken> kickActiveSessions(Long userId) {
        List<AuthSession> actives = sessionRepository.findByUserIdAndStatus(userId, SessionStatus.ACTIVE);
        actives.forEach(AuthSession::markKicked);
        sessionRepository.saveAll(actives);
        return actives.stream().map(session -> new RevocableToken(session.getTokenId(),
                session.getExpiresAt())).toList();
    }

    /**
     * 该用户活跃会话保留指定会话、其余全部置 KICKED，返回被踢会话待撤销清单
     * （REQ-20261119-082531 自助改密 / 下线其他设备：当前会话底账保持 ACTIVE，令牌不撤销）。
     *
     * @param keepTokenId 保留的当前会话 tokenId（=当前访问令牌 jti；底账缺失时语义退化为全踢）
     * @return 被踢会话待撤销清单（不含保留会话）
     */
    @Transactional
    public List<RevocableToken> kickActiveSessionsExcept(Long userId, String keepTokenId) {
        List<AuthSession> actives = sessionRepository.findByUserIdAndStatus(userId, SessionStatus.ACTIVE);
        List<AuthSession> targets = actives.stream()
                .filter(session -> !session.getTokenId().equals(keepTokenId))
                .toList();
        targets.forEach(AuthSession::markKicked);
        sessionRepository.saveAll(targets);
        return targets.stream().map(session -> new RevocableToken(session.getTokenId(),
                session.getExpiresAt())).toList();
    }

    /**
     * 本人活跃且未过期会话集合（REQ-20261119-082531 自助会话列表数据源，确认记录④；
     * 过期会话不出现——与在线用户列表 activeNotExpired 同口径）。
     */
    @Transactional(readOnly = true)
    public List<AuthSession> activeByUserId(Long userId) {
        Instant now = Instant.now();
        return sessionRepository.findByUserIdAndStatus(userId, SessionStatus.ACTIVE).stream()
                .filter(session -> session.getExpiresAt().isAfter(now))
                .toList();
    }

    /**
     * 指定租户全部活跃且未过期会话置 KICKED，返回待撤销清单
     * （REQ-20261117-025200 租户停用批量撤销数据源，确认记录①：枚举撤销，
     * 过期会话不进底账改写——网关 exp 校验兜底失效）；无会话返回空清单（未知租户幂等 0/0）。
     */
    @Transactional
    public List<RevocableToken> kickTenantActiveSessions(String tenantId) {
        List<AuthSession> actives = sessionRepository.findByTenantIdAndStatusAndExpiresAtAfter(
                tenantId, SessionStatus.ACTIVE, Instant.now());
        actives.forEach(AuthSession::markKicked);
        sessionRepository.saveAll(actives);
        return actives.stream().map(session -> new RevocableToken(session.getTokenId(),
                session.getExpiresAt())).toList();
    }

    /** 活跃且未过期会话集合（REQ-20261114-120105 在线用户列表数据源，AC-3 过期会话不出现）。 */
    @Transactional(readOnly = true)
    public List<AuthSession> activeNotExpired() {
        return sessionRepository.findActiveNotExpiredBefore(Instant.now());
    }

    /**
     * 活跃且未过期会话计数（REQ-20261119-011524 确认记录②，GET /internal/v1/online-count 数据源）：
     * 与 activeNotExpired 同口径的 SQL 计数（免权限点内部统计口径，不过滤租户 Q-D04）。
     */
    @Transactional(readOnly = true)
    public long countActive() {
        return sessionRepository.countActiveNotExpiredBefore(Instant.now());
    }

    /**
     * 按 tokenId 定位可踢会话（REQ-20261114-120105 踢下线）：
     * 缺失 / 已终态 / 已过期均返回 empty（调用方统一映射 SESSION_NOT_FOUND）。
     */
    @Transactional(readOnly = true)
    public Optional<AuthSession> kickableByTokenId(String tokenId) {
        return sessionRepository.findByTokenId(tokenId)
                .filter(session -> !session.isTerminal())
                .filter(session -> session.getExpiresAt().isAfter(Instant.now()));
    }

    /** 指定会话置 KICKED（管理端踢下线；底账终态先行，撤销集由调用方在事务外写入）。 */
    @Transactional
    public void kick(AuthSession session) {
        session.markKicked();
        sessionRepository.save(session);
    }

    /** 会话置 LOGOUT；底账缺失（如未及落库的并发登出）不阻断——撤销集为失效唯一判定源。 */
    @Transactional
    public void markLogout(String tokenId) {
        sessionRepository.findByTokenId(tokenId).ifPresent(session -> {
            if (!session.isTerminal()) {
                session.markLogout();
                sessionRepository.save(session);
            }
        });
    }

    /** 会话是否已终态（幂等登出判定之一）。 */
    @Transactional(readOnly = true)
    public boolean isTerminal(String tokenId) {
        return sessionRepository.findByTokenId(tokenId).map(AuthSession::isTerminal).orElse(true);
    }
}
