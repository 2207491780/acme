package com.acme.platform.auth.infrastructure.mapper;

import com.acme.platform.auth.domain.SysLoginLog;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 登录日志 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {
}
