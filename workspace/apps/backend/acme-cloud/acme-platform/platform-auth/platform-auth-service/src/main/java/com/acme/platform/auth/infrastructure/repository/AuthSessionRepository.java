package com.acme.platform.auth.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.domain.SessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 会话底账仓库（按 tokenId / userId / refreshTokenId 定位，无租户过滤 Q-D04）。 */
public interface AuthSessionRepository extends FlexRepository<AuthSession> {

    Optional<AuthSession> findByTokenId(String tokenId);

    Optional<AuthSession> findByRefreshTokenId(String refreshTokenId);

    List<AuthSession> findByUserIdAndStatus(Long userId, SessionStatus status);

    /** 活跃且未过期会话集合（REQ-20261114-120105 在线用户列表数据源，AC-3 过期会话不出现）。 */
    List<AuthSession> findActiveNotExpiredBefore(Instant now);
}
