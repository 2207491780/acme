package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysRoleDept;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 角色-部门绑定 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysRoleDeptMapper extends BaseMapper<SysRoleDept> {
}
