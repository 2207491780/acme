package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysTenantPackageMenu;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 套餐-菜单绑定 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysTenantPackageMenuMapper extends BaseMapper<SysTenantPackageMenu> {
}
