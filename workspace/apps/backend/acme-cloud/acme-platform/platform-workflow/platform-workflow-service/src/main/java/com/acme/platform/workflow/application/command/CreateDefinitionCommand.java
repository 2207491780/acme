package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.bpmn.BpmnDocumentParser;
import com.acme.platform.workflow.compile.ProcessValidationException;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 创建定义草稿命令（契约 API POST /process-definitions；确认⑨ KEY_DUPLICATE 口径：同 key 存在
 * 未部署草稿 → 409；仅存在 DEPLOYED 版本 → 新草稿携带下一版本号 version=max+1）。
 */
@Component
public class CreateDefinitionCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final WfProcessDefinitionRepository definitionRepository;

    public CreateDefinitionCommand(WorkflowCommandSupport support,
                                   WfProcessDefinitionRepository definitionRepository) {
        this.support = support;
        this.definitionRepository = definitionRepository;
    }

    private Integer nextVersion;
    private String processKey;
    private String name;
    private String bpmnXml;

    /** 创建载荷（key/name 必填、bpmnXml 可空）。 */
    public record CreatePayload(String key, String name, String bpmnXml) {
    }

    @Override
    public void load(CommandContext context) {
        CreatePayload payload = context.payload(CreatePayload.class);
        processKey = payload.key() == null ? null : payload.key().trim();
        name = payload.name();
        bpmnXml = payload.bpmnXml();
        nextVersion = definitionRepository.findMaxVersion(processKey).orElse(0) + 1;
    }

    @Override
    public void validate(CommandContext context) {
        if (processKey == null || processKey.isBlank() || name == null || name.isBlank()) {
            throw new com.acme.common.contract.InvalidRequestException("创建定义必填：key / name");
        }
        if (processKey.length() > 64 || name.length() > 128) {
            throw new com.acme.common.contract.InvalidRequestException("key ≤64 / name ≤128");
        }
        // 同 key 未部署草稿唯一（版本链锚点：DEPLOYED 版本不阻断新草稿）
        boolean draftExists = definitionRepository
                .findByStatus(WorkflowDefinitionStatus.DRAFT).stream()
                .anyMatch(existing -> processKey.equals(existing.getProcessKey()));
        if (draftExists) {
            throw new BizException(WorkflowError.WORKFLOW_KEY_DUPLICATE,
                    "同键草稿已存在（先部署或禁用既有草稿）：" + processKey);
        }
        if (bpmnXml != null && !bpmnXml.isBlank()) {
            validateXml(bpmnXml);
        }
    }

    @Override
    public Long execute(CommandContext context) {
        WfProcessDefinition definition = WfProcessDefinition.create(processKey, nextVersion, name,
                WorkflowDefinitionStatus.DRAFT);
        if (bpmnXml != null && !bpmnXml.isBlank()) {
            definition.setBpmnXml(bpmnXml);
        }
        // 雪花 id 落库时分配：结果经链尾读取（Persistence 段之后），此处不可直接返回实体 id
        context.persist(() -> support.saveDefinition(definition));
        context.persist(() -> context.setResult(definition.getId()));
        return null;
    }

    /** XML 结构面校验（保存前置——§5.4 保存/部署双面；解析失败/XML_INVALID）。 */
    static void validateXml(String bpmnXml) {
        try {
            BpmnDocumentParser.parse(bpmnXml);
        } catch (ProcessValidationException ex) {
            throw ex;
        } catch (BpmnDocumentParser.ProcessXmlMalformedException ex) {
            throw new BizException(WorkflowError.WORKFLOW_XML_INVALID, ex.getMessage());
        }
    }
}
