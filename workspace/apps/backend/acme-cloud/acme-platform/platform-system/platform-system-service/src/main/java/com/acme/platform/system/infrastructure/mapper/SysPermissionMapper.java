package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysPermission;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 权限点 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
}
