package com.acme.platform.auth.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.auth.domain.AuthCredential;
import com.acme.platform.auth.infrastructure.mapper.AuthCredentialMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

/** 凭据仓库实现（MyBatis-Flex 底座；按 user_id 唯一定位，无租户过滤 Q-D04）。 */
@Repository
public class AuthCredentialRepositoryImpl extends FlexRepositorySupport<AuthCredential>
        implements AuthCredentialRepository {

    public AuthCredentialRepositoryImpl(AuthCredentialMapper mapper) {
        super(mapper);
    }

    @Override
    public AuthCredential findByUserId(Long userId) {
        return mapper().selectOneByQuery(QueryWrapper.create().where(AuthCredential::getUserId).eq(userId));
    }
}
