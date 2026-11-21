package com.acme.platform.workflow.infrastructure.mapper;

import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * wf_process_definition mapper（@Mapper：standalone 经 starter 按 AutoConfigurationPackages 注册主包；
 * 单体经 MonolithApplication @MapperScan(annotationClass=Mapper.class) 收编，REQ-20261121-142316 聚合）。
 */
@Mapper
public interface WorkflowProcessDefinitionMapper extends BaseMapper<WfProcessDefinition> {
}
