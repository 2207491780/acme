package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.domain.WfProcessDefinition;

import java.util.List;
import java.util.Optional;

/** 流程定义仓储（平台级共享面，无租户过滤——契约确认②：definition 平台级无 tenant_id）。 */
public interface WfProcessDefinitionRepository extends FlexRepository<WfProcessDefinition> {

    /** 定义键当前最大版本（同 key 部署自增 version；无历史=空）。 */
    Optional<Integer> findMaxVersion(String processKey);

    /** 按状态全集（管理面分页）。 */
    List<WfProcessDefinition> findByStatus(WorkflowDefinitionStatus status);
}
