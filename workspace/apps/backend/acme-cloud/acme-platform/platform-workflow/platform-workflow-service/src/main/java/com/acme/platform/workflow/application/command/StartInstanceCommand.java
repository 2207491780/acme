package com.acme.platform.workflow.application.command;

import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.engine.ExecutionEngine;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import com.acme.platform.workflow.kernel.WorkflowEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动流程实例命令（AC-5/AC-12；契约 API POST /process-instances）：校验 DEPLOYED（AC-13 编译
 * 缓存零解析）→ 建实例（RUNNING，initiator=当前主体）→ 根作用域变量初始化 → StartEvent 行为
 * 推进至首个 wait state。
 */
@Component
public class StartInstanceCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final ExecutionEngine executionEngine;

    public StartInstanceCommand(WorkflowCommandSupport support, ExecutionEngine executionEngine) {
        this.support = support;
        this.executionEngine = executionEngine;
    }

    private WfProcessDefinition definition;
    private CompiledProcessModel model;
    private WfProcessInstance instance;
    private WfExecution rootExecution;
    private String tenantId;
    private String initiator;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.StartInstance payload = context.payload(WorkflowPayloads.StartInstance.class);
        definition = support.loadDefinition(payload.definitionId());
        model = definition.getStatus() == WorkflowDefinitionStatus.DEPLOYED
                ? support.loadModel(definition.getId()) : null;
        tenantId = support.requireTenant();
        // initiator：internal 面显式入参（BPM-5 确认③）优先；Admin 面取当前登录用户上下文
        if (payload.initiatorUserId() != null && !payload.initiatorUserId().isBlank()) {
            initiator = payload.initiatorUserId();
        } else {
            var user = com.acme.common.context.UserContext.get();
            initiator = user == null ? null : user.userId();
        }
    }

    @Override
    public void validate(CommandContext context) {
        WorkflowPayloads.StartInstance payload = context.payload(WorkflowPayloads.StartInstance.class);
        if (payload.businessKey() == null || payload.businessKey().isBlank()) {
            throw new com.acme.common.core.BizException(WorkflowError.WORKFLOW_BUSINESS_KEY_REQUIRED);
        }
        // round-2 D5 裁决：启动对 DRAFT/DISABLED 定义统一 02-0003 DEFINITION_NOT_DEPLOYED
        // （api 契约注释为准；01-0005 收窄回「非草稿态保存 XML/再部署」语义）
        if (definition.getStatus() != WorkflowDefinitionStatus.DEPLOYED || model == null) {
            throw new com.acme.common.core.BizException(WorkflowError.WORKFLOW_DEFINITION_NOT_DEPLOYED,
                    "定义未部署或已禁用：" + definition.getId());
        }
        if (initiator == null || initiator.isBlank()) {
            throw new com.acme.common.contract.InvalidRequestException("缺少发起人上下文（UserContext 必绑）");
        }
    }

    @Override
    public Long execute(CommandContext context) {
        WorkflowPayloads.StartInstance payload = context.payload(WorkflowPayloads.StartInstance.class);
        instance = new WfProcessInstance();
        instance.setTenantId(tenantId);
        instance.setDefinitionId(definition.getId());
        instance.setBusinessKey(payload.businessKey());
        instance.setInitiator(initiator);
        instance.setStatus(WorkflowInstanceStatus.RUNNING);
        instance.setStartTime(context.now());
        // 事件随落库登记（雪花 id 落库后才有值——事件承载实例 id；AFTER_COMMIT 异步发布）
        context.persist(() -> {
            support.saveInstance(instance);
            context.recordEvent(new WorkflowEvent(WorkflowEvent.INSTANCE_STARTED, tenantId,
                    definition.getId(), model.getProcessKey(), instance.getId(),
                    model.getStartElementId(), payload.businessKey(), context.now(),
                    Map.of("businessKey", payload.businessKey())));
        });
        var runtime = support.buildRuntime(context, model, instance, tenantId);
        // 根执行先建（scope 链派生的锚点）再写启动变量——命令为有状态单例，字段须每次赋值后
        // 使用，杜绝首启 NPE 与陈旧 execution 的跨实例变量串写
        rootExecution = runtime.newExecution(null, model.getStartElementId(),
                model.element(model.getStartElementId()).type().name(), false, false, false);
        Map<String, Object> initialVariables =
                payload.variables() == null ? Map.of() : payload.variables();
        for (Map.Entry<String, Object> entry : initialVariables.entrySet()) {
            runtime.variablesFor(rootExecution).set(entry.getKey(), entry.getValue());
        }
        runtime.persist(() -> support.saveExecution(rootExecution));
        executionEngine.arrive(runtime, rootExecution, model.getStartElementId());
        executionEngine.drive(runtime);
        // 雪花 id 落库时分配：结果经链尾读取（Persistence 段之后），此处不可直接返回实体 id
        context.persist(() -> context.setResult(instance.getId()));
        return null;
    }
}
