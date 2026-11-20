package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysTenantPackage;

/** 套餐仓库（平台级表，不参与租户过滤；REQ-20261121-022910）。 */
public interface SysTenantPackageRepository extends FlexRepository<SysTenantPackage> {

    boolean existsByPackageName(String packageName);

    /** 重名守卫自排除（update 撞自身名不算重名）。 */
    boolean existsByPackageNameAndIdNot(String packageName, Long id);
}
