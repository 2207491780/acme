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
}
