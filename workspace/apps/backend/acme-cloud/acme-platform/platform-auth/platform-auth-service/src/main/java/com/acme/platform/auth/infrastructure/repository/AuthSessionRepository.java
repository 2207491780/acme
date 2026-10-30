package com.acme.platform.auth.infrastructure.repository;

import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 会话底账仓库（按 tokenId / userId / refreshTokenId 定位，无租户过滤 Q-D04）。 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByTokenId(String tokenId);

    Optional<AuthSession> findByRefreshTokenId(String refreshTokenId);

    List<AuthSession> findByUserIdAndStatus(Long userId, SessionStatus status);
}
