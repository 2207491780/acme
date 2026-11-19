package com.acme.platform.auth.application;

import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.infrastructure.repository.AuthCredentialRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 凭据底账记录器（架构设计 4.6）：upsert 凭据 + 活跃会话置 KICKED 收敛单事务（10.2.1 仅本服务库、
 * 事务内无远程调用 M-05；独立组件防同类自调用绕过事务代理）。
 */
@Component
public class CredentialRecorder {

    private final AuthCredentialRepository credentialRepository;
    private final SessionRecorder sessionRecorder;

    public CredentialRecorder(AuthCredentialRepository credentialRepository, SessionRecorder sessionRecorder) {
        this.credentialRepository = credentialRepository;
        this.sessionRecorder = sessionRecorder;
    }

    /** upsert 凭据（既有归属标注保留）+ 该用户活跃会话置 KICKED；返回待撤销令牌清单。 */
    @Transactional
    public List<SessionRecorder.RevocableToken> resetCredential(Long userId, String passwordHash,
                                                                 String tenantForNew) {
        AuthCredential credential = credentialRepository.findByUserId(userId);
        if (credential == null) {
            credentialRepository.save(new AuthCredential(userId, tenantForNew, passwordHash));
        } else {
            credential.reset(passwordHash);
            credentialRepository.save(credential);
        }
        return sessionRecorder.kickActiveSessions(userId);
    }

    /**
     * upsert 凭据 + 保留当前会话、其余活跃会话置 KICKED（REQ-20261119-082531 自助改密，
     * 确认记录③：当前会话底账保持 ACTIVE，撤销集不含当前令牌——改密后当前登录态保持）。
     *
     * @param keepTokenId 保留的当前会话 tokenId（=当前访问令牌 jti）
     * @return 被踢会话待撤销清单（不含保留会话）
     */
    @Transactional
    public List<SessionRecorder.RevocableToken> resetCredential(Long userId, String passwordHash,
                                                                 String tenantForNew, String keepTokenId) {
        AuthCredential credential = credentialRepository.findByUserId(userId);
        if (credential == null) {
            credentialRepository.save(new AuthCredential(userId, tenantForNew, passwordHash));
        } else {
            credential.reset(passwordHash);
            credentialRepository.save(credential);
        }
        return sessionRecorder.kickActiveSessionsExcept(userId, keepTokenId);
    }
}
