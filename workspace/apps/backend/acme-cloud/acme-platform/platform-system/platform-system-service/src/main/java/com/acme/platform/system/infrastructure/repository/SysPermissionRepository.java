package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 权限点仓库（平台级目录，不参与租户过滤）。 */
public interface SysPermissionRepository extends JpaRepository<SysPermission, Long>,
        JpaSpecificationExecutor<SysPermission> {

    boolean existsByPermissionCodeAndIdNot(String permissionCode, Long id);

    List<SysPermission> findByIdInAndStatus(Collection<Long> ids, com.acme.platform.system.api.EnableStatus status);

    List<SysPermission> findByStatus(com.acme.platform.system.api.EnableStatus status);

    Set<SysPermission> findByIdIn(Collection<Long> ids);
}
