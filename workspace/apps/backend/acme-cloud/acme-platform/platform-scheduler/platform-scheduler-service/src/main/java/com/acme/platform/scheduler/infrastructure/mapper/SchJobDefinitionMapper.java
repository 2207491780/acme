package com.acme.platform.scheduler.infrastructure.mapper;

import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务定义 mapper（@Mapper：standalone 经 starter 按 AutoConfigurationPackages 注册主包；
 * 单体经 MonolithApplication @MapperScan(annotationClass=Mapper.class) 收编，REQ-20261121-071002 确认⑥）。
 */
@Mapper
public interface SchJobDefinitionMapper extends BaseMapper<SchJobDefinition> {
}
