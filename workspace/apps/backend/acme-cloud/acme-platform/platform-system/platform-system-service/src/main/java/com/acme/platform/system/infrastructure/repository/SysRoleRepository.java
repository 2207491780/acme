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
}
