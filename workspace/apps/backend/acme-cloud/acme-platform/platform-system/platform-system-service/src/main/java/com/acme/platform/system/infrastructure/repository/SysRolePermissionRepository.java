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

    /**
     * 定向物理解绑（REQ-20261119-051119 确认② BUTTON 联动差量删除专用）：仅删指定角色下
     * 落在 permissionIds 子集内的绑定行（按钮来源子集），其余来源行不动；空集直接返回 0。
     */
    int deleteByRoleIdAndPermissionIdIn(Long roleId, Collection<Long> permissionIds);
}
