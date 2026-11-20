package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysTenantPackageCapability;

import java.util.List;

/** 套餐-能力绑定仓库（绑定表物理删除；REQ-20261121-054613）。 */
public interface SysTenantPackageCapabilityRepository extends FlexRepository<SysTenantPackageCapability> {

    List<SysTenantPackageCapability> findByPackageId(Long packageId);

    /** 物理解绑（绑定表唯一键决定，全量重绑必须物理删除；返回删除行数）。 */
    int deleteByPackageId(Long packageId);
}
