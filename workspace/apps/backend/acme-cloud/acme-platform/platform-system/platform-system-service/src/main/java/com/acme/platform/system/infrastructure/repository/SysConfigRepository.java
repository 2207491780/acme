package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysConfig;

import java.util.Optional;

/** 租户级系统配置仓库（每租户一行，租户列自动过滤）。 */
public interface SysConfigRepository extends FlexRepository<SysConfig> {

    /** 当前租户配置行（租户过滤由框架注入；无行返回 empty）。 */
    Optional<SysConfig> findCurrent();

    /**
     * 按租户代码直查配置行（REQ-20261117-011514 captcha-policy 内部查询；
     * tenant_id 列存租户代码，跨租户旁路由用例 @IgnoreTenant 声明，无行返回 empty）。
     */
    Optional<SysConfig> findByTenantCode(String tenantCode);
}