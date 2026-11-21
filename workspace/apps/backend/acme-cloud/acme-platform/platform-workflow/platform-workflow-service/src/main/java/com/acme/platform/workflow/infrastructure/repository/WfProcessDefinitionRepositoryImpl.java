package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.infrastructure.mapper.WorkflowProcessDefinitionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 流程定义仓储实现（MyBatis-Flex 底座；平台级共享面无租户列）。 */
@Repository("workflowProcessDefinitionRepository")
public class WfProcessDefinitionRepositoryImpl extends FlexRepositorySupport<WfProcessDefinition>
        implements WfProcessDefinitionRepository {

    public WfProcessDefinitionRepositoryImpl(WorkflowProcessDefinitionMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<Integer> findMaxVersion(String processKey) {
        return mapper().selectListByQuery(QueryWrapper.create()
                        .where(WfProcessDefinition::getProcessKey).eq(processKey)
                        .orderBy(WfProcessDefinition::getDeployVersion, false))
                .stream().findFirst().map(WfProcessDefinition::getDeployVersion);
    }

    @Override
    public List<WfProcessDefinition> findByStatus(WorkflowDefinitionStatus status) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfProcessDefinition::getStatus).eq(status));
    }
}
