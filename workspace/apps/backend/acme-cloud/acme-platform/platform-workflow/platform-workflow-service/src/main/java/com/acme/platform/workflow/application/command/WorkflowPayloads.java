package com.acme.platform.workflow.application.command;

import java.util.Map;

/**
 * 命令调用载荷集（命令内核调用参数；use case → executor.execute(command, payload) 通道）。
 */
public final class WorkflowPayloads {

    private WorkflowPayloads() {
    }

    /** 启动流程实例（POST /process-instances）。 */
    public record StartInstance(Long definitionId, String businessKey, Map<String, Object> variables) {
    }

    /** 任务办结（POST /tasks/{taskId}/complete）。 */
    public record CompleteTask(Long taskId, Map<String, Object> form) {
    }

    /** 任务驳回（POST /tasks/{taskId}/reject；targetNodeId 可空=缺省回退）。 */
    public record RejectTask(Long taskId, String targetNodeId, String comment) {
    }

    /** 任务认领/撤认（claim/unclaim）。 */
    public record ClaimTask(Long taskId) {
    }

    /** 任务转办/委派（transfer/delegate）。 */
    public record TransferTask(Long taskId, String assignee) {
    }

    /** 实例终止（terminate=发起人撤回/管理终止）。 */
    public record TerminateInstance(Long instanceId) {
    }

    /** 实例挂起（suspend；reason 缺省 ADMIN_SUSPEND）。 */
    public record SuspendInstance(Long instanceId, String reason) {
    }

    /** 实例恢复（resume）。 */
    public record ResumeInstance(Long instanceId) {
    }

    /** 定时器触发（internal fire 回调；schedulerExecutionId 可空=本地兜底）。 */
    public record TimerFire(Long timerJobId, String schedulerExecutionId) {
    }

    /** ServiceTask 委托回调（internal callback；result 合并变量后推进）。 */
    public record ServiceTaskCallback(Long executionId, Boolean success,
                                      Map<String, Object> result, String error) {
    }

    /** ServiceTask 重试下发（后台退避面）。 */
    public record ServiceTaskRetry(Long executionId, int attempt) {
    }
}
