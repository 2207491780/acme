package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 菜单仓库（平台级目录，不参与租户过滤；树经应用层组装）。 */
public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    boolean existsByParentId(Long parentId);

    List<SysMenu> findAllByOrderBySortOrderAscIdAsc();
}
