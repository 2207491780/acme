package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysUser;
import com.acme.platform.system.infrastructure.mapper.SysUserMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 用户仓库实现（MyBatis-Flex 底座；租户过滤由框架注入，12.4.1）。 */
@Repository
public class SysUserRepositoryImpl extends FlexRepositorySupport<SysUser> implements SysUserRepository {

    public SysUserRepositoryImpl(SysUserMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByUsername(String username) {
        return exists(QueryWrapper.create().where(SysUser::getUsername).eq(username));
    }

    @Override
    public SysUser findByUsername(String username) {
        return mapper().selectOneByQuery(QueryWrapper.create().where(SysUser::getUsername).eq(username));
    }

    @Override
    @com.acme.framework.tenant.support.IgnoreTenant("switch-session-user-identity-lookup")
    public Optional<SysUser> findByIdGlobal(Long userId) {
        // PK 全局直读（REQ-20261117-025200 切换会话身份解析）：雪花 PK 全局唯一、id 来自已验签令牌，
        // 租户过滤对 PK 等值查询是冗余隔离；切换会话（仅内置 super_admin 可达）身份跨租户解析——
        // 调用方仅 /me 身份汇聚（MeUseCase.loadMe），不扩大旁路使用面（BR-017 评审约束）
        return findById(userId);
    }

    @Override
    public List<SysUser> findByDeptIdIn(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return List.of();
        }
        return mapper().selectListByQuery(QueryWrapper.create().where(SysUser::getDeptId).in(deptIds));
    }

    @Override
    public boolean existsByDeptId(Long deptId) {
        return exists(QueryWrapper.create().where(SysUser::getDeptId).eq(deptId));
    }

    @Override
    public boolean existsByPositionId(Long positionId) {
        return exists(QueryWrapper.create().where(SysUser::getPositionId).eq(positionId));
    }
}
