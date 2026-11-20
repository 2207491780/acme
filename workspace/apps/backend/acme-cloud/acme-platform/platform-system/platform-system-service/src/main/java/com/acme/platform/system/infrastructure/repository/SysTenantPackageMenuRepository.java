package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysTenantPackageMenu;

import java.util.Collection;
import java.util.List;

/** 套餐-菜单绑定仓库（绑定表物理删除；REQ-20261121-022910）。 */
public interface SysTenantPackageMenuRepository extends FlexRepository<SysTenantPackageMenu> {

    List<SysTenantPackageMenu> findByPackageId(Long packageId);

    /** 批量取绑定行（分页 menuCount 聚合用，一次取回免 N+1）。 */
    List<SysTenantPackageMenu> findByPackageIdIn(Collection<Long> packageIds);

    /** 物理解绑（绑定表唯一键决定，全量重绑必须物理删除；返回删除行数）。 */
    int deleteByPackageId(Long packageId);
}
