package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysTenantQuota;
import com.acme.platform.system.infrastructure.mapper.SysTenantQuotaMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 租户配额覆盖仓库实现（MyBatis-Flex 底座；平台级表不过滤租户）。 */
@Repository
public class SysTenantQuotaRepositoryImpl extends FlexRepositorySupport<SysTenantQuota>
        implements SysTenantQuotaRepository {

    public SysTenantQuotaRepositoryImpl(SysTenantQuotaMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<SysTenantQuota> findByTenantId(Long tenantId) {
        return Optional.ofNullable(
                mapper().selectOneByQuery(QueryWrapper.create().where(SysTenantQuota::getTenantId).eq(tenantId)));
    }

    @Override
    public List<SysTenantQuota> findByTenantIdIn(Collection<Long> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(
                QueryWrapper.create().where(SysTenantQuota::getTenantId).in(tenantIds));
    }
}
