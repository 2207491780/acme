package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysRolePermission;

import java.util.Collection;
import java.util.List;

/** 角色-权限点绑定仓库（绑定表物理删除，7.2.7）。 */
public interface SysRolePermissionRepository extends FlexRepository<SysRolePermission> {

    List<SysRolePermission> findByRoleId(Long roleId);

    boolean existsByPermissionId(Long permissionId);

    List<SysRolePermission> findByRoleIdIn(Collection<Long> roleIds);

    /** 物理解绑（绑定表唯一键决定，重绑必须物理删除；返回删除行数）。 */
    int deleteByRoleId(Long roleId);
}
