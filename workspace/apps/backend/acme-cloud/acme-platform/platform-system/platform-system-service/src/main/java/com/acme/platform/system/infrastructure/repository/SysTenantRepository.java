package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysTenant;

import java.util.Optional;

/** 租户仓库（平台级表，不参与租户过滤）。 */
public interface SysTenantRepository extends FlexRepository<SysTenant> {

    /**
     * 租户总数（REQ-20261119-011524 首页概览，确认记录②）：sys_tenant 平台目录表无租户列，
     * 直查即全平台口径（无旁路需求）。
     */
    long countAll();

    boolean existsByTenantCode(String tenantCode);

    Optional<SysTenant> findByTenantCode(String tenantCode);
}
