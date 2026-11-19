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
    @com.acme.framework.tenant.support.IgnoreTenant("dashboard-summary-role-count")
    public long countAllPlatformWide() {
        // 全平台计数（REQ-20261119-011524 首页概览）：跨租户平台口径（12.4.2 显式旁路声明 + 审计）；
        // 独立无排序 QueryWrapper（防 ORDER BY 泄入 count），deleted=false 随默认查询面注入
        return mapper().selectCountByQuery(QueryWrapper.create());
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

    @Override
    @com.acme.framework.tenant.support.IgnoreTenant("built-in-role-cross-tenant-lookup")
    public List<SysRole> findBuiltInByRoleCodeIn(Collection<String> roleCodes) {
        // 旁路查询（平台管理跨租户语义 12.4.2）：仅内置角色行可命中——内置行只能经系统种子路径创建，
        // 租户自建角色（built_in=false）不进本查询面，P0「角色编码非信任锚」语义不回退
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SysRole::getRoleCode).in(roleCodes)
                .and(SysRole::getBuiltIn).eq(true)
                .and(SysRole::getStatus).eq(EnableStatus.ENABLED));
    }
}
