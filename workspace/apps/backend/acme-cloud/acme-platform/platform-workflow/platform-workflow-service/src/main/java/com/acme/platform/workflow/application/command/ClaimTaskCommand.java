package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.engine.AssignmentResolver;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 任务认领命令（AC-5：claim 后 assignee 生效；ALL 策略候选可见可办）：CREATED 且候选含我
 * （或 assignee 已定且=我）→ CLAIMED；已认领再 claim / 终态任务 → STATE_CONFLICT；非候选 →
 * ASSIGNEE_MISMATCH。
 */
@Component
public class ClaimTaskCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final WfTaskRepository taskRepository;
    private final AssignmentResolver assignmentResolver;

    public ClaimTaskCommand(WorkflowCommandSupport support, WfTaskRepository taskRepository,
                            AssignmentResolver assignmentResolver) {
        this.support = support;
        this.taskRepository = taskRepository;
        this.assignmentResolver = assignmentResolver;
    }

    private WfTask task;
    private WfProcessInstance instance;
    private CompiledProcessModel model;
    private EngineRuntime runtime;
    private String operator;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.ClaimTask payload = context.payload(WorkflowPayloads.ClaimTask.class);
        task = taskRepository.findById(payload.taskId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_TASK_NOT_FOUND,
                        "任务不存在：" + payload.taskId()));
        instance = support.loadInstance(task.getInstanceId());
        model = support.loadModel(instance.getDefinitionId());
        runtime = support.buildRuntime(context, model, instance, instance.getTenantId());
        var user = com.acme.common.context.UserContext.get();
        operator = user == null ? null : user.userId();
    }

    @Override
    public void validate(CommandContext context) {
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "实例非 RUNNING 态，拒绝任务操作：" + instance.getId());
        }
        if (task.getStatus() != WorkflowTaskStatus.CREATED) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "任务非未认领态：" + task.getStatus());
        }
        if (operator == null) {
            throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH, "缺少操作人上下文");
        }
        if (task.getAssignee() != null && !operator.equals(task.getAssignee())) {
            throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH,
                    "任务已固定办理人：" + task.getAssignee());
        }
        if (task.getAssignee() == null) {
            var node = model.element(task.getNodeId());
            if (!assignmentResolver.isCandidate(node, runtime.variables(), instance.getInitiator(), operator)) {
                throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH,
                        "操作人不在候选集：" + operator);
            }
        }
    }

    @Override
    public Long execute(CommandContext context) {
        task.setStatus(WorkflowTaskStatus.CLAIMED);
        task.setAssignee(operator);
        task.setClaimTime(context.now());
        context.persist(() -> taskRepository.save(task));
        return task.getId();
    }
}
