package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.ExecutionEngine;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务办结命令（AC-6/AC-7；契约 API POST /tasks/{taskId}/complete）：ALL 策略语义——assignee=me
 * 或（未认领且候选含我）否则 ASSIGNEE_MISMATCH；表单落 form_data jsonb；未认领即办结隐式认领
 * （assignee=操作人，done 查询面锚点）。推进经引擎 leaveTaskNode（注销挂接定时器 + 流出推进）。
 */
@Component
public class CompleteTaskCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final ExecutionEngine executionEngine;
    private final WfTaskRepository taskRepository;
    private final com.acme.platform.workflow.engine.AssignmentResolver assignmentResolver;

    public CompleteTaskCommand(WorkflowCommandSupport support, ExecutionEngine executionEngine,
                               WfTaskRepository taskRepository,
                               com.acme.platform.workflow.engine.AssignmentResolver assignmentResolver) {
        this.support = support;
        this.executionEngine = executionEngine;
        this.taskRepository = taskRepository;
        this.assignmentResolver = assignmentResolver;
    }

    private WfTask task;
    private WfExecution execution;
    private WfProcessInstance instance;
    private CompiledProcessModel model;
    private EngineRuntime runtime;
    private String operator;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.CompleteTask payload = context.payload(WorkflowPayloads.CompleteTask.class);
        task = loadTask(payload.taskId());
        execution = support.loadExecution(task.getExecutionId());
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
        if (task.getStatus() != WorkflowTaskStatus.CREATED && task.getStatus() != WorkflowTaskStatus.CLAIMED) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "任务已离开可办结态：" + task.getStatus());
        }
        if (!canOperate()) {
            throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH,
                    "操作人无权办结任务：" + operator);
        }
    }

    @Override
    public Long execute(CommandContext context) {
        WorkflowPayloads.CompleteTask payload = context.payload(WorkflowPayloads.CompleteTask.class);
        if (task.getAssignee() == null) {
            // 未认领即办结：隐式认领（done 查询面锚点=完成时 assignee）
            task.setAssignee(operator);
        }
        task.setStatus(WorkflowTaskStatus.COMPLETED);
        task.setCompleteTime(context.now());
        if (payload.form() != null && !payload.form().isEmpty()) {
            task.setFormDataJson(support.jsonMapper().writeValueAsString(payload.form()));
        }
        context.persist(() -> taskRepository.save(task));
        context.recordEvent(new com.acme.platform.workflow.kernel.WorkflowEvent(
                com.acme.platform.workflow.kernel.WorkflowEvent.TASK_COMPLETED, runtime.tenantId(),
                model.getProcessKey(), instance.getId(), task.getNodeId(), context.now(),
                Map.of("taskId", String.valueOf(task.getId()))));
        Map<String, Object> leaveDetail = new LinkedHashMap<>();
        leaveDetail.put("taskId", String.valueOf(task.getId()));
        leaveDetail.put("completedBy", operator);
        executionEngine.leaveTaskNode(runtime, execution, leaveDetail);
        executionEngine.drive(runtime);
        return task.getId();
    }

    /** 办结权判定（ALL 策略：assignee=me 或未认领且候选含我）。 */
    private boolean canOperate() {
        if (operator == null) {
            return false;
        }
        if (operator.equals(task.getAssignee())) {
            return true;
        }
        if (task.getAssignee() != null) {
            return false;
        }
        var node = model.element(task.getNodeId());
        return assignmentResolver.isCandidate(node, runtime.variables(), instance.getInitiator(), operator);
    }

    private WfTask loadTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_TASK_NOT_FOUND,
                        "任务不存在：" + taskId));
    }
}
