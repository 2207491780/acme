package com.acme.platform.infra.infrastructure.mapper;

import com.acme.platform.infra.domain.OpException;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 运行异常日志 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface OpExceptionMapper extends BaseMapper<OpException> {
}
