package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysUser;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 用户 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
