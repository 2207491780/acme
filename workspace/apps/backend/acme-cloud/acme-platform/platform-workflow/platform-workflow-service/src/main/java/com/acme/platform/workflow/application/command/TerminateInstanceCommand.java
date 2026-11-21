package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.ExecutionEngine;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 实例终止命令（AC-11；确认⑤撤回语义：实例 TERMINATED + 未完成 execution/task 全 CANCELLED
 * + 定时注销）。发起人撤回与管理终止共用端点（权限面 workflow:instance:manage）。
 */
@Component
public class TerminateInstanceCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final ExecutionEngine executionEngine;

    public TerminateInstanceCommand(WorkflowCommandSupport support, ExecutionEngine executionEngine) {
        this.support = support;
        this.executionEngine = executionEngine;
    }

    private WfProcessInstance instance;
    private CompiledProcessModel model;
    private EngineRuntime runtime;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.TerminateInstance payload =
                context.payload(WorkflowPayloads.TerminateInstance.class);
        instance = support.loadInstance(payload.instanceId());
        model = support.loadModel(instance.getDefinitionId());
        runtime = support.buildRuntime(context, model, instance, instance.getTenantId());
    }

    @Override
    public void validate(CommandContext context) {
        if (instance.getStatus() == WorkflowInstanceStatus.COMPLETED
                || instance.getStatus() == WorkflowInstanceStatus.TERMINATED) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "实例已终态，不可终止：" + instance.getStatus());
        }
    }

    @Override
    public Long execute(CommandContext context) {
        executionEngine.terminateInstance(runtime);
        return instance.getId();
    }
}
