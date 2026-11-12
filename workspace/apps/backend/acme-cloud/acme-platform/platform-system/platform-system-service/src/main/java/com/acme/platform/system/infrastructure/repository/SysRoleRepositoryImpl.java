package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.domain.SysRole;
import com.acme.platform.system.infrastructure.mapper.SysRoleMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 角色仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysRoleRepositoryImpl extends FlexRepositorySupport<SysRole> implements SysRoleRepository {

    public SysRoleRepositoryImpl(SysRoleMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByRoleCode(String roleCode) {
        return exists(QueryWrapper.create().where(SysRole::getRoleCode).eq(roleCode));
    }

    @Override
    public boolean existsByParentId(Long parentId) {
        return exists(QueryWrapper.create().where(SysRole::getParentId).eq(parentId));
    }

    @Override
    public List<SysRole> findByRoleCodeInAndStatus(Collection<String> roleCodes, EnableStatus status) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysRole::getRoleCode).in(roleCodes)
                .and(SysRole::getStatus).eq(status));
    }
}
