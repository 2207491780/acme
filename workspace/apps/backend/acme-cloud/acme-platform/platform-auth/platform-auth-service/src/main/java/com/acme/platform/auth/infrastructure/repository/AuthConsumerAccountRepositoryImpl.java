package com.acme.platform.auth.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.auth.domain.AuthConsumerAccount;
import com.acme.platform.auth.infrastructure.mapper.AuthConsumerAccountMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** C 端账户仓库实现（MyBatis-Flex 底座；手机号唯一定位）。 */
@Repository
public class AuthConsumerAccountRepositoryImpl extends FlexRepositorySupport<AuthConsumerAccount>
        implements AuthConsumerAccountRepository {

    public AuthConsumerAccountRepositoryImpl(AuthConsumerAccountMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<AuthConsumerAccount> findByPhone(String phone) {
        return Optional.ofNullable(
                mapper().selectOneByQuery(QueryWrapper.create().where(AuthConsumerAccount::getPhone).eq(phone)));
    }
}
