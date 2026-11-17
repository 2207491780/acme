package com.acme.platform.infra.infrastructure.mapper;

import com.acme.platform.infra.domain.OpException;
import com.mybatisflex.core.BaseMapper;

/** 运行异常日志 mapper（MyBatis-Flex 面映射；starter 按主包注册，无需 @MapperScan）。 */
public interface OpExceptionMapper extends BaseMapper<OpException> {
}
