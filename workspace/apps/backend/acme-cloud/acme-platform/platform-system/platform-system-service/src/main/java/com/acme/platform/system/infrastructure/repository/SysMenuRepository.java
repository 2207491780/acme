package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysMenu;

import java.util.List;

/** 菜单仓库（平台级目录，不参与租户过滤；树经应用层组装）。 */
public interface SysMenuRepository extends FlexRepository<SysMenu> {

    boolean existsByParentId(Long parentId);

    List<SysMenu> findAllByOrderBySortOrderAscIdAsc();
}
