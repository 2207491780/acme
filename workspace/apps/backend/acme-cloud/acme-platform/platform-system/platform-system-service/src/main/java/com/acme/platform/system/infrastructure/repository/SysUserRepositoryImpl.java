package com.acme.platform.system.infrastructure.repository;

import com.acme.common.core.BizException;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.framework.flex.support.FlexError;
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
    @com.acme.framework.tenant.support.IgnoreTenant("dashboard-summary-user-count")
    public long countAllPlatformWide() {
        // 全平台计数（REQ-20261119-011524 首页概览）：跨租户平台口径（12.4.2 显式旁路声明 + 审计）；
        // 独立无排序 QueryWrapper（防 ORDER BY 泄入 count），deleted=false 随默认查询面注入
        return mapper().selectCountByQuery(QueryWrapper.create());
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
    @com.acme.framework.tenant.support.IgnoreTenant("profile-self-update")
    public SysUser updateSelfProfile(SysUser user) {
        // 本人资料直更（REQ-20261119-082531 确认记录①）：与 findByIdGlobal 同款旁路面——
        // 切换会话下本人行归属源租户，租户条件对 PK+乐观锁更新是冗余且会阻断；PK 直改无越权面
        // （行来自上下文 userId 汇聚的 loadMe 同款链路）；调用方仅 MeUseCase.updateProfile。
        // 乐观锁与版本回填语义对齐 FlexRepositorySupport.save 更新分支（0 行=并发冲突 STALE_WRITE）
        if (mapper().update(user, false) == 0) {
            throw new BizException(FlexError.STALE_WRITE);
        }
        user.setVersion(user.getVersion() + 1);
        return user;
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
