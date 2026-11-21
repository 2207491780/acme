package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.infrastructure.mapper.WorkflowProcessInstanceMapper;
import org.springframework.stereotype.Repository;

/** 流程实例仓储实现（MyBatis-Flex 底座；乐观锁/审计/软删由基类承载）。 */
@Repository("workflowProcessInstanceRepository")
public class WfProcessInstanceRepositoryImpl extends FlexRepositorySupport<WfProcessInstance>
        implements WfProcessInstanceRepository {

    public WfProcessInstanceRepositoryImpl(WorkflowProcessInstanceMapper mapper) {
        super(mapper);
    }
}
