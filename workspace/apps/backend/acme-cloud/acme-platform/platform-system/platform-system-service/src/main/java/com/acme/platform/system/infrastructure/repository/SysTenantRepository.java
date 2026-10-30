package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/** 租户仓库（平台级表，不参与租户过滤）。 */
public interface SysTenantRepository extends JpaRepository<SysTenant, Long>, JpaSpecificationExecutor<SysTenant> {

    boolean existsByTenantCode(String tenantCode);

    Optional<SysTenant> findByTenantCode(String tenantCode);
}
