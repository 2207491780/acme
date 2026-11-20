package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysTenantPackageCapability;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 套餐-能力绑定 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysTenantPackageCapabilityMapper extends BaseMapper<SysTenantPackageCapability> {
}
