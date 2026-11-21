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
 * 任务转办命令（transfer：重设 assignee → CLAIMED）：办理人或候选可转办（ALL 策略面）；
 * 终态任务 → STATE_CONFLICT。
 */
@Component
public class TransferTaskCommand implements WorkflowCommand<String> {

    private final WorkflowCommandSupport support;
    private final WfTaskRepository taskRepository;
    private final com.acme.platform.workflow.engine.AssignmentResolver assignmentResolver;
    private final com.acme.platform.workflow.compile.CompiledProcessModelCache modelCache;

    public TransferTaskCommand(WorkflowCommandSupport support, WfTaskRepository taskRepository,
                               com.acme.platform.workflow.engine.AssignmentResolver assignmentResolver,
                               com.acme.platform.workflow.compile.CompiledProcessModelCache modelCache) {
        this.support = support;
        this.taskRepository = taskRepository;
        this.assignmentResolver = assignmentResolver;
        this.modelCache = modelCache;
    }

    private WfTask task;
    private WfProcessInstance instance;
    private com.acme.platform.workflow.compile.CompiledProcessModel model;
    private com.acme.platform.workflow.engine.EngineRuntime runtime;
    private String operator;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.TransferTask payload = context.payload(WorkflowPayloads.TransferTask.class);
        task = taskRepository.findById(payload.taskId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_TASK_NOT_FOUND,
                        "任务不存在：" + payload.taskId()));
        instance = support.loadInstance(task.getInstanceId());
        model = modelCache.get(instance.getDefinitionId());
        runtime = support.buildRuntime(context, model, instance, instance.getTenantId());
        var user = com.acme.common.context.UserContext.get();
        operator = user == null ? null : user.userId();
    }

    @Override
    public void validate(CommandContext context) {
        WorkflowPayloads.TransferTask payload = context.payload(WorkflowPayloads.TransferTask.class);
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "实例非 RUNNING 态，拒绝任务操作：" + instance.getId());
        }
        if (task.getStatus() != WorkflowTaskStatus.CREATED && task.getStatus() != WorkflowTaskStatus.CLAIMED) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "任务已离开可转办态：" + task.getStatus());
        }
        if (operator == null) {
            throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH, "缺少操作人上下文");
        }
        boolean allowed = operator.equals(task.getAssignee()) || (task.getAssignee() == null
                && assignmentResolver.isCandidate(model.element(task.getNodeId()), runtime.variables(),
                        instance.getInitiator(), operator));
        if (!allowed) {
            throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH,
                    "操作人无权转办任务：" + operator);
        }
        if (payload.assignee() == null || payload.assignee().isBlank()) {
            throw new com.acme.common.contract.InvalidRequestException("转办目标办理人必填");
        }
        if (payload.assignee().equals(task.getAssignee())) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "转办目标与当前办理人相同");
        }
    }

    @Override
    public String execute(CommandContext context) {
        WorkflowPayloads.TransferTask payload = context.payload(WorkflowPayloads.TransferTask.class);
        task.setAssignee(payload.assignee());
        task.setStatus(WorkflowTaskStatus.CLAIMED);
        if (task.getClaimTime() == null) {
            task.setClaimTime(context.now());
        }
        context.persist(() -> taskRepository.save(task));
        return payload.assignee();
    }
}
