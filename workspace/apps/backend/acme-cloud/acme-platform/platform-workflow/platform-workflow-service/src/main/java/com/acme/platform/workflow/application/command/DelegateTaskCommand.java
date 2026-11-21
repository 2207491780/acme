package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.WorkflowTaskStatus;
import com.acme.platform.workflow.api.dto.HistoryActivityView;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.domain.WfHistoryActivity;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.infrastructure.repository.WfHistoryActivityRepository;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务委派命令（确认⑤：一期=转办+owner 记录，委托链单层；AC-6 DELEGATE_STATE_INVALID）：
 * 已委托任务（历史链存在 DELEGATE 记录）再委派 → 409；owner 记录落历史（action=ENTER，
 * detail.delegate={taskId,fromUser,toUser}——委托链单层判定锚点）。
 */
@Component
public class DelegateTaskCommand implements WorkflowCommand<String> {

    private final WorkflowCommandSupport support;
    private final WfTaskRepository taskRepository;
    private final WfHistoryActivityRepository historyRepository;

    public DelegateTaskCommand(WorkflowCommandSupport support, WfTaskRepository taskRepository,
                               WfHistoryActivityRepository historyRepository) {
        this.support = support;
        this.taskRepository = taskRepository;
        this.historyRepository = historyRepository;
    }

    private WfTask task;
    private WfProcessInstance instance;
    private String operator;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.TransferTask payload = context.payload(WorkflowPayloads.TransferTask.class);
        task = taskRepository.findById(payload.taskId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_TASK_NOT_FOUND,
                        "任务不存在：" + payload.taskId()));
        instance = support.loadInstance(task.getInstanceId());
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
                    "任务已离开可委派态：" + task.getStatus());
        }
        // 委托链单层（确认⑤）判定前移（BPM-1 round-1 裁决⑨b）：任务已存在 DELEGATE 记录时，
        // 任何再委派尝试（含原 owner）一律 03-0004 DELEGATE_STATE_INVALID——03-0003 仅归
        // 「非任务关系人」语义（无委托记录且非当前办理人）
        boolean alreadyDelegated = historyRepository.findByInstanceIdOrdered(instance.getId()).stream()
                .anyMatch(row -> hasDelegateForTask(row, task.getId()));
        if (alreadyDelegated) {
            throw new BizException(WorkflowError.WORKFLOW_DELEGATE_STATE_INVALID,
                    "委托链单层已用尽（任务已委派过）：" + task.getId());
        }
        if (operator == null || !operator.equals(task.getAssignee())) {
            throw new BizException(WorkflowError.WORKFLOW_ASSIGNEE_MISMATCH,
                    "仅当前办理人可委派：" + task.getAssignee());
        }
        if (payload.assignee() == null || payload.assignee().isBlank()) {
            throw new com.acme.common.contract.InvalidRequestException("委派目标办理人必填");
        }
        if (payload.assignee().equals(operator)) {
            throw new BizException(WorkflowError.WORKFLOW_DELEGATE_STATE_INVALID,
                    "委派目标不得为本人");
        }
    }

    @Override
    public String execute(CommandContext context) {
        WorkflowPayloads.TransferTask payload = context.payload(WorkflowPayloads.TransferTask.class);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("delegate", Map.of(
                "taskId", String.valueOf(task.getId()),
                "fromUser", operator,
                "toUser", payload.assignee()));
        WfHistoryActivity delegateRecord = new WfHistoryActivity();
        delegateRecord.setTenantId(instance.getTenantId());
        delegateRecord.setInstanceId(instance.getId());
        delegateRecord.setNodeId(task.getNodeId());
        delegateRecord.setNodeType("USER_TASK");
        delegateRecord.setAction(HistoryActivityView.ACTION_ENTER);
        delegateRecord.setAssignee(payload.assignee());
        delegateRecord.setStartAt(context.now());
        delegateRecord.setDetailJson(support.jsonMapper().writeValueAsString(detail));
        context.persist(() -> historyRepository.save(delegateRecord));
        task.setAssignee(payload.assignee());
        task.setStatus(WorkflowTaskStatus.CLAIMED);
        if (task.getClaimTime() == null) {
            task.setClaimTime(context.now());
        }
        context.persist(() -> taskRepository.save(task));
        return payload.assignee();
    }

    /** 历史行委托明细判定（detail.delegate.taskId == taskId）。 */
    private boolean hasDelegateForTask(WfHistoryActivity row, Long taskId) {
        if (row.getDetailJson() == null) {
            return false;
        }
        try {
            Map<?, ?> detail = support.jsonMapper().readValue(row.getDetailJson(), Map.class);
            Object delegate = detail == null ? null : detail.get("delegate");
            if (!(delegate instanceof Map<?, ?> delegateMap)) {
                return false;
            }
            return String.valueOf(taskId).equals(String.valueOf(delegateMap.get("taskId")));
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
