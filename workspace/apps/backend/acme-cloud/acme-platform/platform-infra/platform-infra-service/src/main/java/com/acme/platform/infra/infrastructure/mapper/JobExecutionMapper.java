package com.acme.platform.infra.infrastructure.mapper;

import com.acme.platform.infra.domain.JobExecution;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 任务执行记录 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface JobExecutionMapper extends BaseMapper<JobExecution> {
}
