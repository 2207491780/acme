package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysTenant;
import com.acme.platform.system.infrastructure.mapper.SysTenantMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 租户仓库实现（MyBatis-Flex 底座；平台级表不过滤租户）。 */
@Repository
public class SysTenantRepositoryImpl extends FlexRepositorySupport<SysTenant> implements SysTenantRepository {

    public SysTenantRepositoryImpl(SysTenantMapper mapper) {
        super(mapper);
    }

    @Override
    public long countAll() {
        // 平台目录表直查（REQ-20261119-011524 首页概览）：无租户列不过滤；独立无排序 QueryWrapper
        return mapper().selectCountByQuery(QueryWrapper.create());
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

    @Override
    public List<SysTenant> findByTenantCodeIn(Collection<String> tenantCodes) {
        if (tenantCodes == null || tenantCodes.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(
                QueryWrapper.create().where(SysTenant::getTenantCode).in(tenantCodes));
    }
}
