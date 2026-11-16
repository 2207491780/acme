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
    @com.acme.framework.tenant.support.IgnoreTenant("role-permission-binding-lookup")
    public List<SysRolePermission> findByRoleIdIn(Collection<Long> roleIds) {
        // 旁路查询（PermissionDecisionService 判定面专用）：roleIds 已经「本租户启用角色 + 内置角色」
        // 解析收敛，雪花 id 全局唯一，按 id 取绑定行不扩大信任面——使内置超管在任意租户上下文
        // 持全量权限点绑定（种子绑定行 tenant_id='default'，非内置角色仍严格本租户解析，P0 语义不回退）
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
