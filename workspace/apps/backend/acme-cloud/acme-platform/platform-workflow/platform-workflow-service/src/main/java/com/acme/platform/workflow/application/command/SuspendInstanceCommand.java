package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.dto.ProcessInstanceSuspendRequest;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 实例挂起命令（AC-11）：RUNNING → SUSPENDED（reason 落 suspend_reason；挂起态拒绝新操作，
 * 恢复经 resume）。
 */
@Component
public class SuspendInstanceCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;

    public SuspendInstanceCommand(WorkflowCommandSupport support) {
        this.support = support;
    }

    private WfProcessInstance instance;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.SuspendInstance payload =
                context.payload(WorkflowPayloads.SuspendInstance.class);
        instance = support.loadInstance(payload.instanceId());
    }

    @Override
    public void validate(CommandContext context) {
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "仅 RUNNING 实例可挂起：" + instance.getStatus());
        }
    }

    @Override
    public Long execute(CommandContext context) {
        WorkflowPayloads.SuspendInstance payload =
                context.payload(WorkflowPayloads.SuspendInstance.class);
        instance.setStatus(WorkflowInstanceStatus.SUSPENDED);
        instance.setSuspendReason(payload.reason() == null || payload.reason().isBlank()
                ? ProcessInstanceSuspendRequest.DEFAULT_REASON : payload.reason());
        context.persist(() -> support.saveInstance(instance));
        context.recordEvent(new com.acme.platform.workflow.kernel.WorkflowEvent(
                com.acme.platform.workflow.kernel.WorkflowEvent.INSTANCE_SUSPENDED,
                instance.getTenantId(), instance.getDefinitionId(), null, instance.getId(), null,
                instance.getBusinessKey(), context.now(),
                java.util.Map.of("reason", instance.getSuspendReason())));
        return instance.getId();
    }
}
