package com.acme.platform.auth.infrastructure.mapper;

import com.acme.platform.auth.domain.AuthCredential;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 凭据 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface AuthCredentialMapper extends BaseMapper<AuthCredential> {
}
