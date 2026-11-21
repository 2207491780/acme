package com.acme.platform.workflow.application;

import com.acme.common.context.TenantContext;
import com.acme.common.core.Clock;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.compile.CompiledProcessModelCache;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.VariableScope;
import com.acme.platform.workflow.infrastructure.repository.WfExecutionRepository;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import com.acme.platform.workflow.infrastructure.repository.WfProcessInstanceRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 命令支撑（聚合装载 + EngineRuntime 构建共用面——命令三段式契约的公共底座）。
 * 运行时租户口径：实例聚合携带 tenantId（回放面）；启动命令取 TenantContext（上下文租户）。
 */
@Component
public class WorkflowCommandSupport {

    private final WfProcessDefinitionRepository definitionRepository;
    private final WfProcessInstanceRepository instanceRepository;
    private final WfExecutionRepository executionRepository;
    private final CompiledProcessModelCache modelCache;
    private final VariableService variableService;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final com.acme.common.util.IdGenerator idGenerator;
    private final com.acme.platform.workflow.config.WorkflowProperties properties;

    public WorkflowCommandSupport(WfProcessDefinitionRepository definitionRepository,
                                  WfProcessInstanceRepository instanceRepository,
                                  WfExecutionRepository executionRepository,
                                  CompiledProcessModelCache modelCache,
                                  VariableService variableService,
                                  Clock workflowClock,
                                  JsonMapper jsonMapper,
                                  com.acme.common.util.IdGenerator idGenerator,
                                  com.acme.platform.workflow.config.WorkflowProperties properties) {
        this.definitionRepository = definitionRepository;
        this.instanceRepository = instanceRepository;
        this.executionRepository = executionRepository;
        this.modelCache = modelCache;
        this.variableService = variableService;
        this.clock = workflowClock;
        this.jsonMapper = jsonMapper;
        this.idGenerator = idGenerator;
        this.properties = properties;
    }

    /** 实例装载（租户过滤自动面；未知 → INSTANCE_NOT_FOUND）。 */
    public WfProcessInstance loadInstance(Long instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new com.acme.common.core.BizException(
                        WorkflowError.WORKFLOW_INSTANCE_NOT_FOUND,
                        "流程实例不存在：" + instanceId));
    }

    /** 定义装载（平台级；未知 → DEFINITION_NOT_FOUND）。 */
    public com.acme.platform.workflow.domain.WfProcessDefinition loadDefinition(Long definitionId) {
        return definitionRepository.findById(definitionId)
                .orElseThrow(() -> new com.acme.common.core.BizException(
                        WorkflowError.WORKFLOW_DEFINITION_NOT_FOUND,
                        "流程定义不存在：" + definitionId));
    }

    /** 执行装载（未知 → IllegalArgumentException——执行 id 恒来自任务/定时聚合外键）。 */
    public WfExecution loadExecution(Long executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行不存在：" + executionId));
    }

    /** 实例落库（命令持久化队列操作体）。 */
    public void saveInstance(WfProcessInstance instance) {
        instanceRepository.save(instance);
    }

    /** 执行落库（命令持久化队列操作体）。 */
    public void saveExecution(WfExecution execution) {
        executionRepository.save(execution);
    }

    /** 定义落库（命令持久化队列操作体）。 */
    public void saveDefinition(com.acme.platform.workflow.domain.WfProcessDefinition definition) {
        definitionRepository.save(definition);
    }

    /** 编译缓存失效（部署/禁用面——AC-13 失效重编译）。 */
    public void invalidateModel(Long definitionId) {
        modelCache.invalidate(definitionId);
    }

    /** 编译缓存显式装载（部署面：DEPLOYED 落库前编译先行）。 */
    public CompiledProcessModel loadModelIntoCache(com.acme.platform.workflow.domain.WfProcessDefinition definition) {
        return modelCache.load(definition);
    }

    /** JSON 编解码器（表单/明细落库面）。 */
    public JsonMapper jsonMapper() {
        return jsonMapper;
    }

    /** 域配置（委托缺省超时/重试面）。 */
    public com.acme.platform.workflow.config.WorkflowProperties properties() {
        return properties;
    }

    /** 编译模型装载（运行时零解析——缓存命中即返回，AC-13）。 */
    public CompiledProcessModel loadModel(Long definitionId) {
        return modelCache.get(definitionId);
    }

    /** 命令运行时构建（变量快照/覆盖共享态 + 父链解析器——round-2 D1/D2 scope 链派生制）。 */
    public EngineRuntime buildRuntime(CommandContext context, CompiledProcessModel model,
                                      WfProcessInstance instance, String tenantId) {
        Map<String, com.acme.platform.workflow.domain.WfVariable> snapshot =
                variableService.snapshot(instance.getId());
        VariableSinkAdapter sink = new VariableSinkAdapter(variableService, context,
                instance::getId, () -> tenantId);
        return new EngineRuntime(context, model, instance, tenantId, clock, jsonMapper, sink,
                idGenerator, snapshot, executionRepository::findById);
    }

    /** 上下文租户（启动面：内部令牌强制携带——缺租户显式失败）。 */
    public String requireTenant() {
        TenantContext tenant = TenantContext.get();
        if (tenant == null || tenant.tenantId() == null || tenant.tenantId().isBlank()) {
            throw new com.acme.common.contract.InvalidRequestException("缺少租户上下文（内部令牌必携 tenantId）");
        }
        return tenant.tenantId();
    }
}
