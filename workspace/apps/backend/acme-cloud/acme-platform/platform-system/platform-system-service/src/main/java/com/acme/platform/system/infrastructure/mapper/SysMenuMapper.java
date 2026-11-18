package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysMenu;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 菜单 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {
}
