package com.acme.platform.auth.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.auth.domain.AuthSession;
import com.acme.platform.auth.domain.SessionStatus;
import com.acme.platform.auth.infrastructure.mapper.AuthSessionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 会话底账仓库实现（MyBatis-Flex 底座；按 tokenId / userId / refreshTokenId 定位，无租户过滤 Q-D04）。 */
@Repository
public class AuthSessionRepositoryImpl extends FlexRepositorySupport<AuthSession>
        implements AuthSessionRepository {

    public AuthSessionRepositoryImpl(AuthSessionMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<AuthSession> findByTokenId(String tokenId) {
        return Optional.ofNullable(
                mapper().selectOneByQuery(QueryWrapper.create().where(AuthSession::getTokenId).eq(tokenId)));
    }

    @Override
    public Optional<AuthSession> findByRefreshTokenId(String refreshTokenId) {
        return Optional.ofNullable(mapper().selectOneByQuery(
                QueryWrapper.create().where(AuthSession::getRefreshTokenId).eq(refreshTokenId)));
    }

    @Override
    public List<AuthSession> findByUserIdAndStatus(Long userId, SessionStatus status) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(AuthSession::getUserId).eq(userId)
                .and(AuthSession::getStatus).eq(status));
    }
}
