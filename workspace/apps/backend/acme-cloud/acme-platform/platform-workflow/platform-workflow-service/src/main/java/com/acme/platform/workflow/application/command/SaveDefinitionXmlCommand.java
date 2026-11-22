package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.compile.ProcessValidationException;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 保存定义 XML 命令（契约 API PUT /{id}/xml，草稿态；§5.4 保存前置校验——非法 XML
 * VALIDATION_FAILED 逐条错误；部署态定义保存 XML 拒绝）。
 */
@Component
public class SaveDefinitionXmlCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final WfProcessDefinitionRepository definitionRepository;

    public SaveDefinitionXmlCommand(WorkflowCommandSupport support,
                                    WfProcessDefinitionRepository definitionRepository) {
        this.support = support;
        this.definitionRepository = definitionRepository;
    }

    private WfProcessDefinition definition;
    private String bpmnXml;
    private String layoutJson;

    /** 保存载荷（bpmnXml 必填；layoutJson 可选，随 XML 整体覆盖语义——缺省 null 置 NULL）。 */
    public record SaveXmlPayload(Long definitionId, String bpmnXml, String layoutJson) {
    }

    @Override
    public void load(CommandContext context) {
        SaveXmlPayload payload = context.payload(SaveXmlPayload.class);
        definition = definitionRepository.findById(payload.definitionId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_DEFINITION_NOT_FOUND,
                        "流程定义不存在：" + payload.definitionId()));
        bpmnXml = payload.bpmnXml();
        layoutJson = payload.layoutJson();
    }

    @Override
    public void validate(CommandContext context) {
        if (definition.getStatus() == WorkflowDefinitionStatus.DISABLED) {
            throw new BizException(WorkflowError.WORKFLOW_DEFINITION_DISABLED,
                    "定义已禁用：" + definition.getId());
        }
        if (definition.getStatus() != WorkflowDefinitionStatus.DRAFT) {
            throw new BizException(WorkflowError.WORKFLOW_DEFINITION_DISABLED,
                    "定义非草稿态（版本演进走同键新草稿）：" + definition.getId());
        }
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new com.acme.common.contract.InvalidRequestException("bpmnXml 必填");
        }
        // 保存前置校验（§5.4 全清单；失败逐条 VALIDATION_FAILED）
        DeployDefinitionCommand.validateAndCompile(bpmnXml, definition.getId(), definition.getProcessKey());
    }

    @Override
    public Long execute(CommandContext context) {
        definition.setBpmnXml(bpmnXml);
        // 布局随 XML 整体覆盖（REQ-20261122-023441 确认⑤）：缺省 null 显式置空——save 更新走
        // mapper.update(entity, ignoreNulls=false)，null layout_json 落 NULL（前端保存总是带布局或显式 null）
        definition.setLayoutJson(layoutJson);
        context.persist(() -> support.saveDefinition(definition));
        return definition.getId();
    }
}
