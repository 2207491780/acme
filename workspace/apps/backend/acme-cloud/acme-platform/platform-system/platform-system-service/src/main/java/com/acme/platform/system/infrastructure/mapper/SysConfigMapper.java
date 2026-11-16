package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysConfig;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 租户级系统配置 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}