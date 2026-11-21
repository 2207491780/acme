package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.domain.WfTask;

import java.util.Collection;
import java.util.List;

/** 任务仓储（待办/已办/实例任务树查询面；计数口径见 flex-test-assembly 手册第 5 条）。 */
public interface WfTaskRepository extends FlexRepository<WfTask> {

    /** 按办理人与状态集（todo：assignee=me 且 CREATED/CLAIMED；done：COMPLETED）。 */
    List<WfTask> findByAssigneeAndStatusIn(String assignee, Collection<WorkflowTaskStatus> statuses);

    /** 未认领任务（候选可见性解析：CREATED 且 assignee IS NULL）。 */
    List<WfTask> findByStatusInAndAssigneeIsNull(Collection<WorkflowTaskStatus> statuses);

    /** 实例内活跃任务（详情任务树/定时中断面）。 */
    List<WfTask> findActiveByInstanceId(Long instanceId);

    /** 执行维度任务（ServiceTask 回调/驳回重开定位）。 */
    List<WfTask> findByExecutionId(Long executionId);
}
