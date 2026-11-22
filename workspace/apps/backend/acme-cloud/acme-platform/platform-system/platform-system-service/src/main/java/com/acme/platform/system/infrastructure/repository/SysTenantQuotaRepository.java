package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysTenantQuota;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 租户配额覆盖仓库（平台级表，不参与租户过滤；REQ-20261122-150802 确认③）。 */
public interface SysTenantQuotaRepository extends FlexRepository<SysTenantQuota> {

    /** 按租户 ID 取覆盖行（无覆盖行返回空）。 */
    Optional<SysTenantQuota> findByTenantId(Long tenantId);

    /** 按租户 ID 集批量取覆盖行（列表页配额解析批量面，免 N+1）。 */
    List<SysTenantQuota> findByTenantIdIn(Collection<Long> tenantIds);
}
