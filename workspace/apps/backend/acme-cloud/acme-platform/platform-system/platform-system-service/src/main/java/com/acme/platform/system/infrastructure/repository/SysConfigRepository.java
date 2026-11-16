package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysConfig;

import java.util.Optional;

/** 租户级系统配置仓库（每租户一行，租户列自动过滤）。 */
public interface SysConfigRepository extends FlexRepository<SysConfig> {

    /** 当前租户配置行（租户过滤由框架注入；无行返回 empty）。 */
    Optional<SysConfig> findCurrent();
}