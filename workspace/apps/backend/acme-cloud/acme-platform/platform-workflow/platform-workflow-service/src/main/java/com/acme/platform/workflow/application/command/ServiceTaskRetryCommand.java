package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.bpmn.BpmnElementType;
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
 * ServiceTask 重试下发命令（后台退避面——TimerScheduler.scheduleServiceTaskRetry 回调）：
 * 校验执行仍在 ServiceTask 等待态且实例 RUNNING（挂起/推进后静默退出——退避窗口内状态可能
 * 已被回调/终止改变）；重发经引擎 dispatchServiceTask（attempt 透传，耗尽挂起在引擎裁决）。
 */
@Component
public class ServiceTaskRetryCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final ExecutionEngine executionEngine;
    private final WfExecutionRepository executionRepository;

    public ServiceTaskRetryCommand(WorkflowCommandSupport support, ExecutionEngine executionEngine,
                                   WfExecutionRepository executionRepository) {
        this.support = support;
        this.executionEngine = executionEngine;
        this.executionRepository = executionRepository;
    }

    private WfExecution execution;
    private WfProcessInstance instance;
    private CompiledProcessModel model;
    private EngineRuntime runtime;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.ServiceTaskRetry payload =
                context.payload(WorkflowPayloads.ServiceTaskRetry.class);
        // 重试通道（退避线程无租户上下文）：执行行全局直读 + 行租户绑定命令线程（用例 finally 清理）
        execution = executionRepository.findByIdGlobal(payload.executionId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_INSTANCE_NOT_FOUND,
                        "执行不存在：" + payload.executionId()));
        com.acme.common.context.TenantContext.bind(
                new com.acme.common.context.TenantContext(execution.getTenantId()));
        instance = support.loadInstance(execution.getInstanceId());
        model = support.loadModel(instance.getDefinitionId());
        runtime = support.buildRuntime(context, model, instance, instance.getTenantId());
    }

    @Override
    public void validate(CommandContext context) {
        WorkflowPayloads.ServiceTaskRetry payload =
                context.payload(WorkflowPayloads.ServiceTaskRetry.class);
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING
                || !Boolean.TRUE.equals(execution.getActive())
                || model.element(execution.getNodeId()).type() != BpmnElementType.SERVICE_TASK) {
            // 退避窗口内状态已变化：重试失效（静默退出，非业务失败）
            throw new StaleRetryException();
        }
    }

    @Override
    public Long execute(CommandContext context) {
        WorkflowPayloads.ServiceTaskRetry payload =
                context.payload(WorkflowPayloads.ServiceTaskRetry.class);
        executionEngine.dispatchServiceTask(runtime, execution, payload.attempt());
        return execution.getId();
    }

    /** 重试失效信号（use case 面吞掉——退避窗口竞态，非业务错误）。 */
    public static final class StaleRetryException extends RuntimeException {

        public StaleRetryException() {
            super(null, null, false, false);
        }
    }
}
