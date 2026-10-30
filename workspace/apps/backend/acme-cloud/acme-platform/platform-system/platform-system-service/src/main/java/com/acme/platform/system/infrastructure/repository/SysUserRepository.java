package com.acme.platform.system.infrastructure.repository;

import com.acme.platform.system.domain.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 用户仓库（租户归属，@TenantId 自动过滤 12.4.1）。 */
public interface SysUserRepository extends JpaRepository<SysUser, Long>, JpaSpecificationExecutor<SysUser> {

    boolean existsByUsername(String username);

    /** 登录画像定位（@IgnoreTenant 旁路内显式按全局唯一键查询）。 */
    SysUser findByUsername(String username);
}
