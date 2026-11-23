package com.acme.platform.workflow.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import com.acme.platform.workflow.api.dto.WorkflowVariablesWriteRequest;
import com.acme.platform.workflow.application.WorkflowInternalUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * internal 运行时接口（REQ-20261122-233425 确认③；internal 面 X-Acme-Token 同构 internal 惯例，
 * 不经网关对外，Result 信封——InternalServiceTaskController 同款）：
 * <ul>
 *   <li>POST /internal/v1/workflow/process-instances          → 启动（definitionId+businessKey
 *       +variables+initiatorUserId，业务身份 sub 透传审计）</li>
 *   <li>GET  /internal/v1/workflow/process-instances/{id}     → 运行时详情（实例+变量+活动任务
 *       快照 TaskView）</li>
 *   <li>PUT  /internal/v1/workflow/process-instances/{id}/variables → 变量增量合并写
 *       （复用 VariableService）</li>
 * </ul>
 * 系统侧任务推进（complete by system）不在本期（BPM-6 确认项）；三端点无 DDL。
 * 消费方契约=platform-workflow-api {@code WorkflowRuntimeClient}（路径/方法/形参逐字一致，AC-9）。
 */
@RestController
public class InternalProcessInstanceController {

    private final WorkflowInternalUseCase internalUseCase;

    public InternalProcessInstanceController(WorkflowInternalUseCase internalUseCase) {
        this.internalUseCase = internalUseCase;
    }

    /** 启动（DEPLOYED 校验+StartEvent 推进同 Admin 口径；initiator=入参业务发起人）。 */
    @PostMapping("/internal/v1/workflow/process-instances")
    public Result<Long> start(@Valid @RequestBody InternalProcessInstanceStartRequest request) {
        return Result.ok(internalUseCase.start(request.definitionId(), request.businessKey(),
                request.variables(), request.initiatorUserId()));
    }

    /** 运行时详情（实例+变量 Map+活动任务快照 TaskView；未知 id → 404 信封）。 */
    @GetMapping("/internal/v1/workflow/process-instances/{id}")
    public Result<WorkflowInstanceDetailView> detail(@PathVariable Long id) {
        return Result.ok(internalUseCase.runtimeDetail(id));
    }

    /** 变量增量合并写（空 Map 忽略；uk(instance_id,name) 冲突转更新）。 */
    @PutMapping("/internal/v1/workflow/process-instances/{id}/variables")
    public Result<Void> writeVariables(@PathVariable Long id,
                                       @Valid @RequestBody WorkflowVariablesWriteRequest request) {
        internalUseCase.writeVariables(id, request.variables());
        return Result.ok(null);
    }
}
