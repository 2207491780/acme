package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysUser;

import java.util.Collection;
import java.util.List;

/** 用户仓库（租户归属，租户列自动过滤 12.4.1）。 */
public interface SysUserRepository extends FlexRepository<SysUser> {

    boolean existsByUsername(String username);

    /** 登录画像定位（@IgnoreTenant 旁路内显式按全局唯一键查询）。 */
    SysUser findByUsername(String username);

    /** 部门内用户（数据范围可见集解析 / 岗位引用判定按 id 维度另行判定）。 */
    List<SysUser> findByDeptIdIn(Collection<Long> deptIds);

    boolean existsByDeptId(Long deptId);

    boolean existsByPositionId(Long positionId);
}
