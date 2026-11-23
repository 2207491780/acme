package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.compile.CompiledElement;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfHistoryActivity;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.ExecutionEngine;
import com.acme.platform.workflow.engine.HistoryRecorder;
import com.acme.platform.workflow.infrastructure.repository.WfHistoryActivityRepository;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import com.acme.platform.workflow.api.dto.HistoryActivityView;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务驳回命令（确认⑤驳回语义终稿；AC-6）：targetNodeId 缺省=回退到上一个已完成 UserTask
 * （历史 LEAVE 链倒序；无历史 UserTask 回退 StartEvent 发起新一轮）；指定=回退到任意历史
 * UserTask/StartEvent。驳回产生新任务实例、历史链保留并记 REJECTED 边。
 */
@Component
public class RejectTaskCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final ExecutionEngine executionEngine;
    private final WfTaskRepository taskRepository;
    private final WfHistoryActivityRepository historyRepository;
    private final HistoryRecorder historyRecorder;

    public RejectTaskCommand(WorkflowCommandSupport support, ExecutionEngine executionEngine,
                             WfTaskRepository taskRepository,
                             WfHistoryActivityRepository historyRepository,
                             HistoryRecorder historyRecorder) {
        this.support = support;
        this.executionEngine = executionEngine;
        this.taskRepository = taskRepository;
        this.historyRepository = historyRepository;
        this.historyRecorder = historyRecorder;
    }

    private WfTask task;
    private WfExecution execution;
    private WfProcessInstance instance;
    private CompiledProcessModel model;
    private EngineRuntime runtime;
    private String operator;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.RejectTask payload = context.payload(WorkflowPayloads.RejectTask.class);
        task = taskRepository.findById(payload.taskId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_TASK_NOT_FOUND,
                        "任务不存在：" + payload.taskId()));
        execution = support.loadExecution(task.getExecutionId());
        instance = support.loadInstance(task.getInstanceId());
        model = support.loadModel(instance.getDefinitionId());
        runtime = support.buildRuntime(context, model, instance, instance.getTenantId());
        var user = com.acme.common.context.UserContext.get();
        operator = user == null ? null : user.userId();
    }

    @Override
    public void validate(CommandContext context) {
        WorkflowPayloads.RejectTask payload = context.payload(WorkflowPayloads.RejectTask.class);
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "实例非 RUNNING 态，拒绝任务操作：" + instance.getId());
        }
        if (task.getStatus() != WorkflowTaskStatus.CREATED && task.getStatus() != WorkflowTaskStatus.CLAIMED) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "任务已离开可驳回态：" + task.getStatus());
        }
        String targetNodeId = resolveTarget(payload.targetNodeId());
        CompiledElement target = model.getElements().get(targetNodeId);
        if (target == null) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "回退目标节点不存在：" + targetNodeId);
        }
        boolean targetAllowed = target.type() == com.acme.platform.workflow.bpmn.BpmnElementType.START_EVENT
                || target.type() == com.acme.platform.workflow.bpmn.BpmnElementType.USER_TASK;
        if (!targetAllowed || targetNodeId.equals(task.getNodeId())) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "回退目标非法（仅历史 UserTask/StartEvent 且不得为当前节点）：" + targetNodeId);
        }
        if (target.type() == com.acme.platform.workflow.bpmn.BpmnElementType.USER_TASK
                && historyRepository.findByInstanceIdOrdered(instance.getId()).stream()
                        .noneMatch(row -> targetNodeId.equals(row.getNodeId()))) {
            throw new BizException(WorkflowError.WORKFLOW_TASK_STATE_CONFLICT,
                    "回退目标不在历史链：" + targetNodeId);
        }
    }

    @Override
    public Long execute(CommandContext context) {
        WorkflowPayloads.RejectTask payload = context.payload(WorkflowPayloads.RejectTask.class);
        String targetNodeId = resolveTarget(payload.targetNodeId());
        task.setStatus(WorkflowTaskStatus.REJECTED);
        context.persist(() -> taskRepository.save(task));
        Map<String, Object> edgeDetail = new LinkedHashMap<>();
        edgeDetail.put("fromNodeId", task.getNodeId());
        edgeDetail.put("targetNodeId", targetNodeId);
        edgeDetail.put("taskId", String.valueOf(task.getId()));
        if (payload.comment() != null && !payload.comment().isBlank()) {
            edgeDetail.put("comment", payload.comment());
        }
        historyRecorder.rejectEdge(runtime, targetNodeId, edgeDetail);
        // 驳回事件（BPM-5 增补埋点：reject 不产生 TASK_COMPLETED——业务侧驳回终态回写锚点）
        context.recordEvent(new com.acme.platform.workflow.kernel.WorkflowEvent(
                com.acme.platform.workflow.kernel.WorkflowEvent.TASK_REJECTED, runtime.tenantId(),
                instance.getDefinitionId(), model.getProcessKey(), instance.getId(), task.getNodeId(),
                instance.getBusinessKey(), context.now(), Map.of(
                        "taskId", String.valueOf(task.getId()), "rejectedTo", targetNodeId)));
        executionEngine.rejectToNode(runtime, execution, targetNodeId, Map.of(
                "taskId", String.valueOf(task.getId()), "rejectedTo", targetNodeId));
        executionEngine.drive(runtime);
        return task.getId();
    }

    /** 回退目标解析（缺省=历史最近一个已完成 UserTask LEAVE；无历史回退 StartEvent 发起新一轮）。 */
    private String resolveTarget(String targetNodeId) {
        if (targetNodeId != null && !targetNodeId.isBlank()) {
            return targetNodeId.trim();
        }
        return historyRepository.findByInstanceIdOrdered(instance.getId()).stream()
                .filter(row -> HistoryActivityView.ACTION_LEAVE.equals(row.getAction()))
                .filter(row -> "USER_TASK".equals(row.getNodeType()))
                .filter(row -> !task.getNodeId().equals(row.getNodeId()))
                .max(Comparator.comparing(WfHistoryActivity::getStartAt)
                        .thenComparing(WfHistoryActivity::getId))
                .map(WfHistoryActivity::getNodeId)
                .orElseGet(() -> model.getStartElementId());
    }
}
