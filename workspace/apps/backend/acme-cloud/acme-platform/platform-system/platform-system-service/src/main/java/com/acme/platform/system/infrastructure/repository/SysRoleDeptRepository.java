package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysRoleDept;

import java.util.List;

/** 角色-部门绑定仓库（绑定表物理删除，7.2.7）。 */
public interface SysRoleDeptRepository extends FlexRepository<SysRoleDept> {

    List<SysRoleDept> findByRoleId(Long roleId);

    List<SysRoleDept> findByDeptId(Long deptId);

    boolean existsByDeptId(Long deptId);

    /** 物理解绑（绑定表唯一键决定，重写必须物理删除；返回删除行数）。 */
    int deleteByRoleId(Long roleId);
}
