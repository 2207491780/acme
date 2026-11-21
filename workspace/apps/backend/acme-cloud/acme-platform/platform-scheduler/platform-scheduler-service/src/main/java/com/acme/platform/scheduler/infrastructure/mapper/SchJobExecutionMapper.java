package com.acme.platform.scheduler.infrastructure.mapper;

import com.acme.platform.scheduler.domain.SchJobExecution;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 执行历史 mapper。 */
@Mapper
public interface SchJobExecutionMapper extends BaseMapper<SchJobExecution> {
}
