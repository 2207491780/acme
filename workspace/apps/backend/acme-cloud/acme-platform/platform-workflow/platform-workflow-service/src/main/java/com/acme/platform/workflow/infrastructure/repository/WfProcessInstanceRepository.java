package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.domain.WfProcessInstance;

/** 流程实例仓储（租户隔离由 @TenantId 列自动过滤，12.4.1）。 */
public interface WfProcessInstanceRepository extends FlexRepository<WfProcessInstance> {
}
