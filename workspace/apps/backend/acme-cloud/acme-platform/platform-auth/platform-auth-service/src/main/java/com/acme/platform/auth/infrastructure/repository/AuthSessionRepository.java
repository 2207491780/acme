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

    /**
     * 活跃且未过期会话计数（REQ-20261119-011524 在线数统计数据源，确认记录②）：
     * 计数独立无排序 QueryWrapper（防 ORDER BY 泄入 count），列表同口径（status=ACTIVE 且未过期）。
     */
    long countActiveNotExpiredBefore(Instant now);

    /**
     * 指定租户活跃且未过期会话集合（REQ-20261117-025200 租户停用批量撤销数据源，确认记录①：
     * tenant_id 归属列 + status=ACTIVE + 未过期；tenant_id 存租户代码，全局定位不过滤）。
     */
    List<AuthSession> findByTenantIdAndStatusAndExpiresAtAfter(String tenantId, SessionStatus status, Instant now);
}
