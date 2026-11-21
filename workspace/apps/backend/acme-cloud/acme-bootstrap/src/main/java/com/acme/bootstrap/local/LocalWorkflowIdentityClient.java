package com.acme.bootstrap.local;

import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.WorkflowIdentityClient;
import com.acme.platform.system.application.WorkflowIdentityUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 单体本地短路适配器（REQ-20261121-142316：workflow 身份目录跨域 Feign 的本地短路，同
 * LocalTenantClient 惯例——{@code @Primary} + Feign 惰性化解，跨域调用全部短路至本地适配器）。
 * 直调 system 域 {@link WorkflowIdentityUseCase}，行为等价于 InternalWorkflowIdentityController 的
 * /internal/v1/workflow-identities 端点（宽目录语义：未知主体返回空集/null）。
 * 上下文口径：透明透传调用方 UserContext/TenantContext（租户过滤随用例查询面）。
 */
@Component
@Primary
public class LocalWorkflowIdentityClient implements WorkflowIdentityClient {

    private final WorkflowIdentityUseCase workflowIdentityUseCase;

    public LocalWorkflowIdentityClient(WorkflowIdentityUseCase workflowIdentityUseCase) {
        this.workflowIdentityUseCase = workflowIdentityUseCase;
    }

    @Override
    public ContractResult<Set<String>> userRoleCodes(Long userId) {
        return LocalContractResults.ok(workflowIdentityUseCase.userRoleCodes(userId));
    }

    @Override
    public ContractResult<Long> userDeptId(Long userId) {
        return LocalContractResults.ok(workflowIdentityUseCase.userDeptId(userId));
    }

    @Override
    public ContractResult<Set<Long>> roleUserIds(String roleCode) {
        return LocalContractResults.ok(workflowIdentityUseCase.roleUserIds(roleCode));
    }

    @Override
    public ContractResult<Set<Long>> deptUserIds(Long deptId) {
        return LocalContractResults.ok(workflowIdentityUseCase.deptUserIds(deptId));
    }
}
