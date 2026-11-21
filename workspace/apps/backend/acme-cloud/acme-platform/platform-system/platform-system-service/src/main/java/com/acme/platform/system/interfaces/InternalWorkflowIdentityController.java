package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.application.WorkflowIdentityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 服务间用户身份目录内部接口（REQ-20261121-142316；契约 WorkflowIdentityClient 服务端，
 * 不经网关对外）。宽目录语义：未知主体返回空集/null（宽表查询，不抛 404）。
 * 出参经 framework-web StandardResponseAdvice 统一 Result 信封（FR-003）。
 */
@RestController
public class InternalWorkflowIdentityController {

    private final WorkflowIdentityUseCase workflowIdentityUseCase;

    public InternalWorkflowIdentityController(WorkflowIdentityUseCase workflowIdentityUseCase) {
        this.workflowIdentityUseCase = workflowIdentityUseCase;
    }

    @GetMapping("/internal/v1/workflow-identities/users/{userId}/role-codes")
    public Result<Set<String>> userRoleCodes(@PathVariable Long userId) {
        return Result.ok(workflowIdentityUseCase.userRoleCodes(userId));
    }

    @GetMapping("/internal/v1/workflow-identities/users/{userId}/dept-id")
    public Result<Long> userDeptId(@PathVariable Long userId) {
        return Result.ok(workflowIdentityUseCase.userDeptId(userId));
    }

    @GetMapping("/internal/v1/workflow-identities/roles/{roleCode}/user-ids")
    public Result<Set<Long>> roleUserIds(@PathVariable String roleCode) {
        return Result.ok(workflowIdentityUseCase.roleUserIds(roleCode));
    }

    @GetMapping("/internal/v1/workflow-identities/depts/{deptId}/user-ids")
    public Result<Set<Long>> deptUserIds(@PathVariable Long deptId) {
        return Result.ok(workflowIdentityUseCase.deptUserIds(deptId));
    }
}
