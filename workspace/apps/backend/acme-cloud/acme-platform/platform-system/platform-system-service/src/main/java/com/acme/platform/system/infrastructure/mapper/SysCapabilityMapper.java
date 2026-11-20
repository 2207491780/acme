package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysCapability;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 服务能力 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysCapabilityMapper extends BaseMapper<SysCapability> {
}
