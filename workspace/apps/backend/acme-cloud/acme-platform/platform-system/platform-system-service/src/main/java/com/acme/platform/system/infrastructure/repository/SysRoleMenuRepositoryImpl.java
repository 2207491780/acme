package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysRoleMenu;
import com.acme.platform.system.infrastructure.mapper.SysRoleMenuMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 角色-菜单绑定仓库实现（MyBatis-Flex 底座；解绑经旁路物理删除——绑定表唯一键决定，7.2.7）。 */
@Repository
public class SysRoleMenuRepositoryImpl extends FlexRepositorySupport<SysRoleMenu>
        implements SysRoleMenuRepository {

    public SysRoleMenuRepositoryImpl(SysRoleMenuMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysRoleMenu> findByRoleId(Long roleId) {
        return mapper().selectListByQuery(QueryWrapper.create().where(SysRoleMenu::getRoleId).eq(roleId));
    }

    @Override
    public boolean existsByMenuId(Long menuId) {
        return exists(QueryWrapper.create().where(SysRoleMenu::getMenuId).eq(menuId));
    }

    @Override
    public List<SysRoleMenu> findByRoleIdIn(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(QueryWrapper.create().where(SysRoleMenu::getRoleId).in(roleIds));
    }

    @Override
    public int deleteByRoleId(Long roleId) {
        return forceDeleteByQuery(QueryWrapper.create().where(SysRoleMenu::getRoleId).eq(roleId));
    }
}
