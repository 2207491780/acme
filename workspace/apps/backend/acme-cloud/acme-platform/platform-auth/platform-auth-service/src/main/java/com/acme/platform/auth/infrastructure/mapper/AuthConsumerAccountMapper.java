package com.acme.platform.auth.infrastructure.mapper;

import com.acme.platform.auth.domain.AuthConsumerAccount;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** C 端账户 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface AuthConsumerAccountMapper extends BaseMapper<AuthConsumerAccount> {
}
