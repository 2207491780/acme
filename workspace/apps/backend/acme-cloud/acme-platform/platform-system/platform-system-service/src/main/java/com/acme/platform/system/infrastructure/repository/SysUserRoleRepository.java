package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysUserRole;

import java.util.List;

/** 用户-角色绑定仓库（绑定表物理删除，7.2.7）。 */
public interface SysUserRoleRepository extends FlexRepository<SysUserRole> {

    List<SysUserRole> findByUserId(Long userId);

    boolean existsByRoleId(Long roleId);

    boolean existsByUserIdAndRoleId(Long userId, Long roleId);

    /** 物理解绑（绑定表唯一键决定，重绑必须物理删除；返回删除行数）。 */
    int deleteByUserId(Long userId);

    List<SysUserRole> findByUserIdIn(List<Long> userIds);
}
