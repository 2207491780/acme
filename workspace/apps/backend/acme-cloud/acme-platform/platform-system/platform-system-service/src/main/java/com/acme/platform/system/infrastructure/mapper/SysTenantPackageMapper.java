package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysTenantPackage;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 套餐 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysTenantPackageMapper extends BaseMapper<SysTenantPackage> {
}
