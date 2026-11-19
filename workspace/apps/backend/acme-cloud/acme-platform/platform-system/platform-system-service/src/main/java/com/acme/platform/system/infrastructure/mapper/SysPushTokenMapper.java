package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysPushToken;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 推送令牌 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysPushTokenMapper extends BaseMapper<SysPushToken> {
}
