package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 禁用定义命令（AC-13/AC-14 语义面：新实例拒绝、运行实例不受影响）：DEPLOYED/DRAFT → DISABLED
 * + 编译缓存失效；幂等（已 DISABLED 直接返回）。
 */
@Component
public class DisableDefinitionCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final WfProcessDefinitionRepository definitionRepository;

    public DisableDefinitionCommand(WorkflowCommandSupport support,
                                    WfProcessDefinitionRepository definitionRepository) {
        this.support = support;
        this.definitionRepository = definitionRepository;
    }

    private WfProcessDefinition definition;

    /** 禁用载荷。 */
    public record DisablePayload(Long definitionId) {
    }

    @Override
    public void load(CommandContext context) {
        DisablePayload payload = context.payload(DisablePayload.class);
        definition = definitionRepository.findById(payload.definitionId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_DEFINITION_NOT_FOUND,
                        "流程定义不存在：" + payload.definitionId()));
    }

    @Override
    public void validate(CommandContext context) {
        if (definition.getStatus() == WorkflowDefinitionStatus.DISABLED) {
            throw new BizException(WorkflowError.WORKFLOW_DEFINITION_DISABLED,
                    "定义已禁用（幂等拒绝重复禁用信封）：" + definition.getId());
        }
    }

    @Override
    public Long execute(CommandContext context) {
        definition.setStatus(WorkflowDefinitionStatus.DISABLED);
        context.persist(() -> support.saveDefinition(definition));
        // 编译缓存失效（AC-13：disable → 失效重编译语义）
        support.invalidateModel(definition.getId());
        return definition.getId();
    }
}
