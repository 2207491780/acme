package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.bpmn.BpmnDocumentParser;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.compile.ProcessValidationException;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 部署定义命令（AC-2/AC-13；契约 API POST /{id}/deploy）：结构校验（§5.4 全清单，逐条错误）→
 * 编译缓存生成（先编译后落状态——非法 XML 永不 DEPLOYED）→ status=DEPLOYED + deployed_at；
 * 首部署（DRAFT）沿用建稿版本号；DEPLOYED/DISABLED 再部署=同键版本自增（A-6 form 1：
 * redeploy version+1，编译缓存随新版本装载）。
 */
@Component
public class DeployDefinitionCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final WfProcessDefinitionRepository definitionRepository;

    public DeployDefinitionCommand(WorkflowCommandSupport support,
                                   WfProcessDefinitionRepository definitionRepository) {
        this.support = support;
        this.definitionRepository = definitionRepository;
    }

    private WfProcessDefinition definition;
    private CompiledProcessModel compiled;
    private Integer nextVersion;

    /** 部署载荷。 */
    public record DeployPayload(Long definitionId) {
    }

    @Override
    public void load(CommandContext context) {
        DeployPayload payload = context.payload(DeployPayload.class);
        definition = definitionRepository.findById(payload.definitionId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_DEFINITION_NOT_FOUND,
                        "流程定义不存在：" + payload.definitionId()));
        nextVersion = definitionRepository.findMaxVersion(definition.getProcessKey())
                .orElse(definition.getDeployVersion()) + 1;
    }

    @Override
    public void validate(CommandContext context) {
        if (definition.getBpmnXml() == null || definition.getBpmnXml().isBlank()) {
            throw new BizException(WorkflowError.WORKFLOW_XML_INVALID, "部署前必须保存 BPMN XML");
        }
        // 结构校验 + 编译（校验失败逐条 VALIDATION_FAILED；先编译后落状态）
        compiled = validateAndCompile(definition.getBpmnXml(), definition.getId(),
                definition.getProcessKey());
    }

    @Override
    public Long execute(CommandContext context) {
        // 再部署（DEPLOYED/DISABLED）= 同键版本自增（uk(process_key, deploy_version) 满足：
        // 取全局 max+1）；首部署（DRAFT）沿用建稿分配版本
        if (definition.getStatus() != WorkflowDefinitionStatus.DRAFT) {
            definition.setDeployVersion(nextVersion);
        }
        definition.setStatus(WorkflowDefinitionStatus.DEPLOYED);
        definition.setDeployedAt(context.now());
        context.persist(() -> support.saveDefinition(definition));
        // 编译缓存装载（redeploy 面同键覆盖旧版本模型；AC-13 缓存生成/失效）
        support.loadModelIntoCache(definition);
        return definition.getId();
    }

    /** 校验 + 编译（save/deploy 双面前置共用面；失败抛 ProcessValidationException 携逐条明细——
     *  控制器层转 VALIDATION_FAILED 信封 data；事务随异常回滚，AC-12）。 */
    static CompiledProcessModel validateAndCompile(String bpmnXml, Long definitionId, String processKey) {
        BpmnDocumentParser.ParseResult parseResult;
        try {
            parseResult = BpmnDocumentParser.parse(bpmnXml);
        } catch (BpmnDocumentParser.ProcessXmlMalformedException ex) {
            throw new BizException(WorkflowError.WORKFLOW_XML_INVALID, ex.getMessage());
        }
        try {
            return com.acme.platform.workflow.compile.ProcessModelCompiler.compile(
                    definitionId, processKey, parseResult);
        } catch (ProcessValidationException ex) {
            throw ex;
        }
    }
}
