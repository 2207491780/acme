package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysRolePermission;
import com.acme.platform.system.infrastructure.mapper.SysRolePermissionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 角色-权限点绑定仓库实现（MyBatis-Flex 底座；解绑经旁路物理删除——绑定表唯一键决定，7.2.7）。 */
@Repository
public class SysRolePermissionRepositoryImpl extends FlexRepositorySupport<SysRolePermission>
        implements SysRolePermissionRepository {

    public SysRolePermissionRepositoryImpl(SysRolePermissionMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysRolePermission> findByRoleId(Long roleId) {
        return mapper().selectListByQuery(QueryWrapper.create().where(SysRolePermission::getRoleId).eq(roleId));
    }

    @Override
    public boolean existsByPermissionId(Long permissionId) {
        return exists(QueryWrapper.create().where(SysRolePermission::getPermissionId).eq(permissionId));
    }

    @Override
    public List<SysRolePermission> findByRoleIdIn(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysRolePermission::getRoleId).in(roleIds));
    }

    @Override
    public int deleteByRoleId(Long roleId) {
        return forceDeleteByQuery(QueryWrapper.create().where(SysRolePermission::getRoleId).eq(roleId));
    }
}
