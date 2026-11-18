package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysPermission;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 权限点仓库（平台级目录，不参与租户过滤）。 */
public interface SysPermissionRepository extends FlexRepository<SysPermission> {

    boolean existsByPermissionCodeAndIdNot(String permissionCode, Long id);

    List<SysPermission> findByIdInAndStatus(Collection<Long> ids, EnableStatus status);

    List<SysPermission> findByStatus(EnableStatus status);

    Set<SysPermission> findByIdIn(Collection<Long> ids);
}
