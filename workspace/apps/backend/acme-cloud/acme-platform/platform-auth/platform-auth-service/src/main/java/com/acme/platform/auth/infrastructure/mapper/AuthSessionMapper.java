package com.acme.platform.auth.infrastructure.mapper;

import com.acme.platform.auth.domain.AuthSession;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 会话底账 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSession> {
}
