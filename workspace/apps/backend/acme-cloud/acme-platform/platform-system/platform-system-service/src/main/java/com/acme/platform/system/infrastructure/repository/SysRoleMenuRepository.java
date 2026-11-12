package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysRoleMenu;

import java.util.Collection;
import java.util.List;

/** 角色-菜单绑定仓库（绑定表物理删除，7.2.7）。 */
public interface SysRoleMenuRepository extends FlexRepository<SysRoleMenu> {

    List<SysRoleMenu> findByRoleId(Long roleId);

    boolean existsByMenuId(Long menuId);

    List<SysRoleMenu> findByRoleIdIn(Collection<Long> roleIds);

    /** 物理解绑（绑定表唯一键决定，重绑必须物理删除；返回删除行数）。 */
    int deleteByRoleId(Long roleId);
}
