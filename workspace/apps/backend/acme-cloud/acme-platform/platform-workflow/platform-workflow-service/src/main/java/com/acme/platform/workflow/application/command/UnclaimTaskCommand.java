package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 任务撤认命令（AC-6：未认领 unclaim → STATE_CONFLICT）：CLAIMED 且 assignee=me → CREATED
 * （assignee/claim_time 清空回候选池）；非本人撤认 → ASSIGNEE_MISMATCH。
 */
@Component
public class UnclaimTaskCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final WfTaskRepository taskRepository;

    public UnclaimTaskCommand(WorkflowCommandSupport support, WfTaskRepository taskRepository) {
        this.support = support;
        this.taskRepository = taskRepository;
    }

    private WfTask task;
    private WfProcessInstance instance;
    private String operator;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.ClaimTask payload = context.payload(WorkflowPayloads.ClaimTask.class);
        task = taskRepository.findById(payload.taskId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_TASK_NOT_FOUND,
                        "任务不存在：" + payload.taskId()));
        instance = support.loadInstance(task.getInstanceId());
        var user = com.acme.common.context.UserContext.get();
        operator = user == null ? null : user.userId();
    }

    @Override
    public void validate(CommandContext context) {
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "实例非 RUNNING 态，拒绝任务操作：" + instance.getId());
        }
        if (task.getStatus() != WorkflowTaskStatus.CLAIMED) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "任务非已认领态，不可撤认：" + task.getStatus());
        }
        if (operator == null || !operator.equals(task.getAssignee())) {
            throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH,
                    "仅当前办理人可撤认：" + task.getAssignee());
        }
    }

    @Override
    public Long execute(CommandContext context) {
        task.setStatus(WorkflowTaskStatus.CREATED);
        task.setAssignee(null);
        task.setClaimTime(null);
        context.persist(() -> taskRepository.save(task));
        return task.getId();
    }
}
