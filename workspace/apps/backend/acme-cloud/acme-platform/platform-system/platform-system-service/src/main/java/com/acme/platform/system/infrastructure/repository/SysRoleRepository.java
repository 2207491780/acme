package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

/** 角色仓库（租户归属，@TenantId 自动过滤）。 */
public interface SysRoleRepository extends JpaRepository<SysRole, Long>, JpaSpecificationExecutor<SysRole> {

    boolean existsByRoleCode(String roleCode);

    boolean existsByParentId(Long parentId);

    List<SysRole> findByRoleCodeInAndStatus(Collection<String> roleCodes,
                                            com.acme.platform.system.api.EnableStatus status);
}
