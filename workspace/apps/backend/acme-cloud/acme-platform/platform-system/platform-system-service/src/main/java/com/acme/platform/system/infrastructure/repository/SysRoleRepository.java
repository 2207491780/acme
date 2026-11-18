package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.api.EnableStatus;

import java.util.Collection;
import java.util.List;

/** 角色仓库（租户归属，租户列自动过滤）。 */
public interface SysRoleRepository extends FlexRepository<SysRole> {

    boolean existsByRoleCode(String roleCode);

    boolean existsByParentId(Long parentId);

    List<SysRole> findByRoleCodeInAndStatus(Collection<String> roleCodes, EnableStatus status);

    /**
     * 内置角色跨租户定位（built_in 行仅系统路径可建，保留编码防租户自建同名提权；
     * 旁路查询由实现 @IgnoreTenant 声明，PermissionDecisionService 跨租户兜底用）。
     */
    List<SysRole> findBuiltInByRoleCodeIn(Collection<String> roleCodes);
}
