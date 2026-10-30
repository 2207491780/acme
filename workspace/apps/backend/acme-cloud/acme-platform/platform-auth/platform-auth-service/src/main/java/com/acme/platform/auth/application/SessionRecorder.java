package com.acme.platform.auth.application;

import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.domain.SessionStatus;
import com.acme.platform.auth.infrastructure.repository.AuthSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
