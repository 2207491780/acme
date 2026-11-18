package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysTenant;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 租户 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {
}
