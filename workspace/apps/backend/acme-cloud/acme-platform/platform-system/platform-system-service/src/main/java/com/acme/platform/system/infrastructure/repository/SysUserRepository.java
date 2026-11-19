package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysUser;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 用户仓库（租户归属，租户列自动过滤 12.4.1）。 */
public interface SysUserRepository extends FlexRepository<SysUser> {

    boolean existsByUsername(String username);

    /** 登录画像定位（@IgnoreTenant 旁路内显式按全局唯一键查询）。 */
    SysUser findByUsername(String username);

    /**
     * 按 PK 全局直读（REQ-20261117-025200 切换会话身份解析，12.4.2 显式旁路声明）：
     * PK 全局唯一、id 来自已验签令牌，租户过滤对 PK 等值查询是冗余隔离；
     * 服务「切换会话（仅内置 super_admin 可达）身份跨租户解析」口径——
     * 超管切换到目标租户后 /me 按上下文 userId 取本人主数据（行归属源租户）。
     */
    Optional<SysUser> findByIdGlobal(Long userId);

    /**
     * 本人资料直更（REQ-20261119-082531 确认记录①，12.4.2 显式旁路声明 accessPoint
     * profile-self-update）：切换会话下本人行归属源租户，租户条件对 PK+乐观锁更新冗余且会阻断；
     * 实体须先经 findByIdGlobal 汇聚（与 /me 视图同款链路，无越权面）。乐观锁冲突 → STALE_WRITE。
     */
    SysUser updateSelfProfile(SysUser user);

    /** 部门内用户（数据范围可见集解析 / 岗位引用判定按 id 维度另行判定）。 */
    List<SysUser> findByDeptIdIn(Collection<Long> deptIds);

    boolean existsByDeptId(Long deptId);

    boolean existsByPositionId(Long positionId);
}
