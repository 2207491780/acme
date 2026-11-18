package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysRolePermission;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 角色-权限点绑定 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {
}
