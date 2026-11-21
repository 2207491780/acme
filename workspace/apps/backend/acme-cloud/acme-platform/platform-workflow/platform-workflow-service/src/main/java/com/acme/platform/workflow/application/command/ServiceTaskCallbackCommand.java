package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.compile.CompiledElement;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.ExecutionEngine;
import com.acme.platform.workflow.infrastructure.repository.WfExecutionRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * ServiceTask 委托回调命令（AC-8；契约 internal POST /service-tasks/{executionId}/callback）：
 * success=true → result 并入变量后推进；success=false → 按历史失败轮次 +1 重试下发
 * （acme:retryBackoffMs 退避），耗尽 → 实例 SUSPENDED(SERVICE_TASK_FAILED)。
 */
@Component
public class ServiceTaskCallbackCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final ExecutionEngine executionEngine;
    private final WfExecutionRepository executionRepository;
    private final com.acme.platform.workflow.infrastructure.repository.WfHistoryActivityRepository
            historyRepository;
    private final com.acme.platform.workflow.engine.TimerScheduler timerScheduler;

    public ServiceTaskCallbackCommand(WorkflowCommandSupport support,
                                      ExecutionEngine executionEngine,
                                      WfExecutionRepository executionRepository,
                                      com.acme.platform.workflow.infrastructure.repository.WfHistoryActivityRepository historyRepository,
                                      com.acme.platform.workflow.engine.TimerScheduler timerScheduler) {
        this.support = support;
        this.executionEngine = executionEngine;
        this.executionRepository = executionRepository;
        this.historyRepository = historyRepository;
        this.timerScheduler = timerScheduler;
    }

    private WfExecution execution;
    private WfProcessInstance instance;
    private CompiledProcessModel model;
    private CompiledElement node;
    private EngineRuntime runtime;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.ServiceTaskCallback payload =
                context.payload(WorkflowPayloads.ServiceTaskCallback.class);
        // 回调通道（业务服务内部令牌可能不携行租户）：执行行全局直读 + 行租户绑定命令线程
        // （HTTP 路径由 InternalTokenAuthFilter finally 清理；退避线程路径由用例 finally 清理）
        execution = executionRepository.findByIdGlobal(payload.executionId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_INSTANCE_NOT_FOUND,
                        "执行不存在：" + payload.executionId()));
        com.acme.common.context.TenantContext.bind(
                new com.acme.common.context.TenantContext(execution.getTenantId()));
        instance = support.loadInstance(execution.getInstanceId());
        model = support.loadModel(instance.getDefinitionId());
        node = model.element(execution.getNodeId());
        runtime = support.buildRuntime(context, model, instance, instance.getTenantId());
    }

    @Override
    public void validate(CommandContext context) {
        WorkflowPayloads.ServiceTaskCallback payload =
                context.payload(WorkflowPayloads.ServiceTaskCallback.class);
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "实例非 RUNNING 态，拒绝回调推进：" + instance.getStatus());
        }
        if (!Boolean.TRUE.equals(execution.getActive())
                || node.type() != BpmnElementType.SERVICE_TASK) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "执行非 ServiceTask 等待态：" + execution.getId());
        }
        if (payload.success() == null) {
            throw new com.acme.common.contract.InvalidRequestException("回调 success 必填");
        }
    }

    @Override
    public Long execute(CommandContext context) {
        WorkflowPayloads.ServiceTaskCallback payload =
                context.payload(WorkflowPayloads.ServiceTaskCallback.class);
        if (Boolean.TRUE.equals(payload.success())) {
            // 成功：result 并入当前作用域变量（写不穿透语义随作用域）后推进
            if (payload.result() != null) {
                for (var entry : payload.result().entrySet()) {
                    runtime.variablesFor(execution).set(entry.getKey(), entry.getValue());
                }
            }
            executionEngine.leaveTaskNode(runtime, execution, java.util.Map.of(
                    "serviceTask", node.id(), "callback", "success"));
            executionEngine.drive(runtime);
            return execution.getId();
        }
        // 失败：失败轮次 = 历史 SERVICE_TASK 失败明细计数（含本次——回调失败与下发失败同构落明细，
        // 否则耗尽判定计数恒 0、重试永不收敛）；超上限挂起（AC-8），否则退避重发
        int priorFailures = countDispatchFailures(node.id());
        recordCallbackFailure(context, node, priorFailures, payload.error());
        int attempt = priorFailures;
        int maxAttempts = retryMaxAttempts(node);
        if (attempt >= maxAttempts) {
            executionEngine.suspendInstance(runtime,
                    com.acme.platform.workflow.api.WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "SERVICE_TASK_FAILED");
            return execution.getId();
        }
        long backoffMs = retryBackoffMs(node);
        timerScheduler.scheduleServiceTaskRetry(execution.getId(), attempt + 1, backoffMs);
        return execution.getId();
    }

    /** 回调失败明细落历史（action=LEAVE + detail.serviceTask——与下发失败明细同构，耗尽计数锚点）。 */
    private void recordCallbackFailure(CommandContext context, CompiledElement node, int attempt,
                                       String error) {
        com.acme.platform.workflow.domain.WfHistoryActivity failureRow =
                new com.acme.platform.workflow.domain.WfHistoryActivity();
        failureRow.setTenantId(instance.getTenantId());
        failureRow.setInstanceId(instance.getId());
        failureRow.setNodeId(node.id());
        failureRow.setNodeType(node.type().name());
        failureRow.setAction("LEAVE");
        failureRow.setStartAt(context.now());
        failureRow.setDetailJson(support.jsonMapper().writeValueAsString(java.util.Map.of(
                "serviceTask", node.id(), "attempt", attempt,
                "error", error == null || error.isBlank() ? "callback-failure" : error)));
        context.persist(() -> historyRepository.save(failureRow));
    }

    /** 历史失败明细计数（LEAVE 行 detail.serviceTask == node.id）。 */
    private int countDispatchFailures(String nodeId) {
        return (int) historyRepository.findByInstanceIdOrdered(instance.getId()).stream()
                .filter(row -> "LEAVE".equals(row.getAction()) && nodeId.equals(row.getNodeId()))
                .filter(row -> row.getDetailJson() != null && row.getDetailJson().contains("serviceTask"))
                .count();
    }

    private int retryMaxAttempts(CompiledElement serviceNode) {
        String raw = serviceNode.acme("retryMaxAttempts");
        return raw == null ? support.properties().getDelegate().getRetryMaxAttempts()
                : Integer.parseInt(raw.trim());
    }

    private long retryBackoffMs(CompiledElement serviceNode) {
        String raw = serviceNode.acme("retryBackoffMs");
        return raw == null ? support.properties().getDelegate().getRetryBackoffMs()
                : Long.parseLong(raw.trim());
    }
}
