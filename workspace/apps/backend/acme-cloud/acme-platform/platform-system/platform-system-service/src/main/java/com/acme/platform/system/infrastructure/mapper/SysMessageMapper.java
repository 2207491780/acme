package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysMessage;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 站内信 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysMessageMapper extends BaseMapper<SysMessage> {
}
