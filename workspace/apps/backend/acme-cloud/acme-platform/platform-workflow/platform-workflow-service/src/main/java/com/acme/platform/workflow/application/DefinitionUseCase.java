package com.acme.platform.workflow.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.api.dto.ProcessDefinitionView;
import com.acme.platform.workflow.application.command.CreateDefinitionCommand;
import com.acme.platform.workflow.application.command.DeployDefinitionCommand;
import com.acme.platform.workflow.application.command.DisableDefinitionCommand;
import com.acme.platform.workflow.application.command.SaveDefinitionXmlCommand;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.compile.ProcessValidationException;
import com.acme.platform.workflow.compile.ValidationError;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.common.core.BizException;
import com.acme.platform.workflow.kernel.WorkflowCommandExecutor;
import com.acme.platform.workflow.WorkflowError;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 定义域用例（命令内核承载写路径——ContextInit→Retry→Load→Validation→Execution→Persistence→
 * EventPublish；查询直查不走命令链，设计规格 §3.1）。
 */
@Service("workflowDefinitionUseCase")
public class DefinitionUseCase {

    private final WorkflowCommandExecutor executor;
    private final CreateDefinitionCommand createDefinitionCommand;
    private final SaveDefinitionXmlCommand saveDefinitionXmlCommand;
    private final DeployDefinitionCommand deployDefinitionCommand;
    private final DisableDefinitionCommand disableDefinitionCommand;
    private final WfProcessDefinitionRepository definitionRepository;
    private final com.acme.platform.workflow.compile.CompiledProcessModelCache modelCache;

    public DefinitionUseCase(WorkflowCommandExecutor executor,
                             CreateDefinitionCommand createDefinitionCommand,
                             SaveDefinitionXmlCommand saveDefinitionXmlCommand,
                             DeployDefinitionCommand deployDefinitionCommand,
                             DisableDefinitionCommand disableDefinitionCommand,
                             WfProcessDefinitionRepository definitionRepository,
                             com.acme.platform.workflow.compile.CompiledProcessModelCache modelCache) {
        this.executor = executor;
        this.createDefinitionCommand = createDefinitionCommand;
        this.saveDefinitionXmlCommand = saveDefinitionXmlCommand;
        this.deployDefinitionCommand = deployDefinitionCommand;
        this.disableDefinitionCommand = disableDefinitionCommand;
        this.definitionRepository = definitionRepository;
        this.modelCache = modelCache;
    }

    /** 创建草稿（返回定义 id）。 */
    public Long create(String key, String name, String bpmnXml) {
        return executor.execute(createDefinitionCommand,
                new CreateDefinitionCommand.CreatePayload(key, name, bpmnXml));
    }

    /** 保存 XML（草稿态；结构校验失败 → ProcessValidationException 携逐条明细，控制器转信封）。 */
    public Long saveXml(Long definitionId, String bpmnXml) {
        return executor.execute(saveDefinitionXmlCommand,
                new SaveDefinitionXmlCommand.SaveXmlPayload(definitionId, bpmnXml));
    }

    /** 部署（结构校验 → 编译缓存 → DEPLOYED）。 */
    public Long deploy(Long definitionId) {
        return executor.execute(deployDefinitionCommand, new DeployDefinitionCommand.DeployPayload(definitionId));
    }

    /** 禁用（新实例拒绝、运行实例不受影响；编译缓存失效 AC-13）。 */
    public Long disable(Long definitionId) {
        return executor.execute(disableDefinitionCommand,
                new DisableDefinitionCommand.DisablePayload(definitionId));
    }

    /** 详情（含 bpmnXml；未知 → 404）。 */
    public ProcessDefinitionView get(Long definitionId) {
        WfProcessDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_DEFINITION_NOT_FOUND,
                        "流程定义不存在：" + definitionId));
        return viewOf(definition, true);
    }

    /** 分页（keyword 匹配 key/name；status 过滤；key+version 排序）。 */
    public PageResult<ProcessDefinitionView> list(String keyword, WorkflowDefinitionStatus status,
                                                  PageQuery pageQuery) {
        QueryWrapper query = QueryWrapper.create();
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            query = query.where(WfProcessDefinition::getProcessKey).like(like)
                    .or(WfProcessDefinition::getName).like(like);
        }
        if (status != null) {
            query = query.and(WfProcessDefinition::getStatus).eq(status);
        }
        query = query.orderBy(WfProcessDefinition::getProcessKey, true)
                .orderBy(WfProcessDefinition::getDeployVersion, true);
        var page = definitionRepository.findAll(query, pageQuery.page(), pageQuery.size());
        return new PageResult<>(page.records().stream().map(d -> viewOf(d, false)).toList(), page.total());
    }

    /** 编译模型预览（部署态返回编译面节点表——BPM-3/4 设计器消费位）。 */
    public CompiledProcessModel compiledModel(Long definitionId) {
        return modelCache.get(definitionId);
    }

    /** 校验错误明细透出（AC-2 逐条错误面）。 */
    public List<ValidationError> validationErrorsOf(ProcessValidationException ex) {
        return ex.getErrors();
    }

    private ProcessDefinitionView viewOf(WfProcessDefinition definition, boolean withXml) {
        return new ProcessDefinitionView(definition.getId(), definition.getProcessKey(),
                definition.getName(), definition.getDeployVersion(), definition.getStatus(),
                withXml ? definition.getBpmnXml() : null, definition.getLayoutJson(),
                definition.getCreatedAt(), definition.getDeployedAt());
    }
}
