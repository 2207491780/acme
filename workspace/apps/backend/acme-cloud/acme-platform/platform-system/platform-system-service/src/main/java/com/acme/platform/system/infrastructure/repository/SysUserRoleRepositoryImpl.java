package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysUserRole;
import com.acme.platform.system.infrastructure.mapper.SysUserRoleMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 用户-角色绑定仓库实现（MyBatis-Flex 底座；解绑经旁路物理删除——绑定表唯一键决定，7.2.7）。 */
@Repository
public class SysUserRoleRepositoryImpl extends FlexRepositorySupport<SysUserRole>
        implements SysUserRoleRepository {

    public SysUserRoleRepositoryImpl(SysUserRoleMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SysUserRole> findByUserId(Long userId) {
        return mapper().selectListByQuery(QueryWrapper.create().where(SysUserRole::getUserId).eq(userId));
    }

    @Override
    public boolean existsByRoleId(Long roleId) {
        return exists(QueryWrapper.create().where(SysUserRole::getRoleId).eq(roleId));
    }

    @Override
    public boolean existsByUserIdAndRoleId(Long userId, Long roleId) {
        return exists(QueryWrapper.create()
                .where(SysUserRole::getUserId).eq(userId)
                .and(SysUserRole::getRoleId).eq(roleId));
    }

    @Override
    public int deleteByUserId(Long userId) {
        return forceDeleteByQuery(QueryWrapper.create().where(SysUserRole::getUserId).eq(userId));
    }

    @Override
    public List<SysUserRole> findByUserIdIn(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(QueryWrapper.create().where(SysUserRole::getUserId).in(userIds));
    }
}
