package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 实例恢复命令（AC-11）：SUSPENDED → RUNNING（suspend_reason 清空；NO_ASSIGNEE_RESOLVED 挂起
 * 经候选目录恢复后 todo 重新可见）。
 */
@Component
public class ResumeInstanceCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;

    public ResumeInstanceCommand(WorkflowCommandSupport support) {
        this.support = support;
    }

    private WfProcessInstance instance;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.ResumeInstance payload =
                context.payload(WorkflowPayloads.ResumeInstance.class);
        instance = support.loadInstance(payload.instanceId());
    }

    @Override
    public void validate(CommandContext context) {
        if (instance.getStatus() != WorkflowInstanceStatus.SUSPENDED) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "仅 SUSPENDED 实例可恢复：" + instance.getStatus());
        }
    }

    @Override
    public Long execute(CommandContext context) {
        instance.setStatus(WorkflowInstanceStatus.RUNNING);
        instance.setSuspendReason(null);
        context.persist(() -> support.saveInstance(instance));
        context.recordEvent(new com.acme.platform.workflow.kernel.WorkflowEvent(
                com.acme.platform.workflow.kernel.WorkflowEvent.INSTANCE_RESUMED,
                instance.getTenantId(), instance.getDefinitionId(), null, instance.getId(), null,
                instance.getBusinessKey(), context.now(), java.util.Map.of()));
        return instance.getId();
    }
}
