package com.acme.bootstrap.local;

import com.acme.platform.system.api.ContractResult;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import com.acme.platform.workflow.api.dto.WorkflowVariablesWriteRequest;
import com.acme.platform.workflow.application.WorkflowInternalUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 单体本地短路适配器（REQ-20261122-233425 确认⑥：workflow 运行时契约跨服务 Feign 的本地短路，
 * 同 {@code LocalWorkflowIdentityClient} 惯例——{@code @Primary} + Feign 惰性化解，跨域调用全部
 * 短路至本地适配器）。直调 workflow 域 {@link WorkflowInternalUseCase} 等价面，行为等价
 * InternalProcessInstanceController 的 /internal/v1/workflow/process-instances 三端点
 * （internal 启动/运行时详情/变量增量合并写——分布式形态经 Feign + X-Acme-Token，语义一致）。
 * 上下文口径：透明透传调用方 UserContext/TenantContext（启动 initiator=请求体显式入参，
 * internal 面语义与 HTTP 端点相同）。
 */
@Component("localWorkflowRuntimeClient")
@Primary
public class LocalWorkflowRuntimeClient implements WorkflowRuntimeClient {

    private final WorkflowInternalUseCase internalUseCase;

    public LocalWorkflowRuntimeClient(WorkflowInternalUseCase internalUseCase) {
        this.internalUseCase = internalUseCase;
    }

    @Override
    public ContractResult<Long> startProcessInstance(InternalProcessInstanceStartRequest request) {
        return LocalContractResults.ok(internalUseCase.start(request.definitionId(),
                request.businessKey(), request.variables(), request.initiatorUserId()));
    }

    @Override
    public ContractResult<WorkflowInstanceDetailView> processInstanceDetail(Long id) {
        return LocalContractResults.ok(internalUseCase.runtimeDetail(id));
    }

    @Override
    public ContractResult<Void> writeVariables(Long id, WorkflowVariablesWriteRequest request) {
        Map<String, Object> variables = request == null ? null : request.variables();
        internalUseCase.writeVariables(id, variables);
        return LocalContractResults.ok(null);
    }
}
