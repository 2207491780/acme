package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysPermission;
import com.acme.platform.system.infrastructure.mapper.SysPermissionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 权限点仓库实现（MyBatis-Flex 底座；平台级目录不过滤租户）。 */
@Repository
public class SysPermissionRepositoryImpl extends FlexRepositorySupport<SysPermission>
        implements SysPermissionRepository {

    public SysPermissionRepositoryImpl(SysPermissionMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByPermissionCodeAndIdNot(String permissionCode, Long id) {
        return exists(QueryWrapper.create()
                .where(SysPermission::getPermissionCode).eq(permissionCode)
                .and(SysPermission::getId).ne(id));
    }

    @Override
    public List<SysPermission> findByIdInAndStatus(Collection<Long> ids, EnableStatus status) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysPermission::getId).in(ids)
                .and(SysPermission::getStatus).eq(status));
    }

    @Override
    public List<SysPermission> findByStatus(EnableStatus status) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysPermission::getStatus).eq(status));
    }

    @Override
    public Set<SysPermission> findByIdIn(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(mapper().selectListByQuery(QueryWrapper.create()
                .where(SysPermission::getId).in(ids)));
    }
}
