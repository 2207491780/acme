package com.acme.platform.scheduler.infrastructure.mapper;

import com.acme.platform.scheduler.domain.SchExecutorRegistry;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 执行器注册 mapper。 */
@Mapper
public interface SchExecutorRegistryMapper extends BaseMapper<SchExecutorRegistry> {
}
