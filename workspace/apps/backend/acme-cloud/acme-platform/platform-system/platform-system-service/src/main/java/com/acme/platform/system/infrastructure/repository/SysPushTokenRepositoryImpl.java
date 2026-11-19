package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysPushToken;
import com.acme.platform.system.infrastructure.mapper.SysPushTokenMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 推送令牌仓库实现（MyBatis-Flex 底座；无租户列不触发租户过滤）。 */
@Repository
public class SysPushTokenRepositoryImpl extends FlexRepositorySupport<SysPushToken>
        implements SysPushTokenRepository {

    public SysPushTokenRepositoryImpl(SysPushTokenMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<SysPushToken> findByToken(String token) {
        return Optional.ofNullable(mapper().selectOneByQuery(
                QueryWrapper.create().where(SysPushToken::getToken).eq(token)));
    }

    @Override
    public List<SysPushToken> findByUserId(Long userId) {
        return mapper().selectListByQuery(
                QueryWrapper.create().where(SysPushToken::getUserId).eq(userId));
    }

    @Override
    public void deleteByTokenAndUserId(String token, Long userId) {
        forceDeleteByQuery(QueryWrapper.create()
                .where(SysPushToken::getToken).eq(token)
                .and(SysPushToken::getUserId).eq(userId));
    }
}
