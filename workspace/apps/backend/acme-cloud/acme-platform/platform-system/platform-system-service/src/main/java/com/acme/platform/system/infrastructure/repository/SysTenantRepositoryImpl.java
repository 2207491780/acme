package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.infrastructure.mapper.SysTenantMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 租户仓库实现（MyBatis-Flex 底座；平台级表不过滤租户）。 */
@Repository
public class SysTenantRepositoryImpl extends FlexRepositorySupport<SysTenant> implements SysTenantRepository {

    public SysTenantRepositoryImpl(SysTenantMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByTenantCode(String tenantCode) {
        return exists(QueryWrapper.create().where(SysTenant::getTenantCode).eq(tenantCode));
    }

    @Override
    public Optional<SysTenant> findByTenantCode(String tenantCode) {
        return Optional.ofNullable(
                mapper().selectOneByQuery(QueryWrapper.create().where(SysTenant::getTenantCode).eq(tenantCode)));
    }
}
