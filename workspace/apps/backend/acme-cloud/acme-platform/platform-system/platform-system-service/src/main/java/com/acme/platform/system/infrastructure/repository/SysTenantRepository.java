package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysTenant;

import java.util.Optional;

/** 租户仓库（平台级表，不参与租户过滤）。 */
public interface SysTenantRepository extends FlexRepository<SysTenant> {

    boolean existsByTenantCode(String tenantCode);

    Optional<SysTenant> findByTenantCode(String tenantCode);
}
