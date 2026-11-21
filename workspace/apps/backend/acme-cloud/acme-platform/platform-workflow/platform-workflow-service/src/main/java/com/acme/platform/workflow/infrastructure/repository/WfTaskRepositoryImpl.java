package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.infrastructure.mapper.WorkflowTaskMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 任务仓储实现（MyBatis-Flex 底座；分页走 paginate，计数独立无排序 wrapper）。 */
@Repository("workflowTaskRepository")
public class WfTaskRepositoryImpl extends FlexRepositorySupport<WfTask>
        implements WfTaskRepository {

    public WfTaskRepositoryImpl(WorkflowTaskMapper mapper) {
        super(mapper);
    }

    @Override
    public List<WfTask> findByAssigneeAndStatusIn(String assignee, Collection<WorkflowTaskStatus> statuses) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfTask::getAssignee).eq(assignee)
                .and(WfTask::getStatus).in(statuses));
    }

    @Override
    public List<WfTask> findByStatusInAndAssigneeIsNull(Collection<WorkflowTaskStatus> statuses) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfTask::getStatus).in(statuses)
                .and(WfTask::getAssignee).isNull());
    }

    @Override
    public List<WfTask> findActiveByInstanceId(Long instanceId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfTask::getInstanceId).eq(instanceId)
                .and(WfTask::getStatus).in(List.of(WorkflowTaskStatus.CREATED, WorkflowTaskStatus.CLAIMED)));
    }

    @Override
    public List<WfTask> findByExecutionId(Long executionId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfTask::getExecutionId).eq(executionId));
    }
}
