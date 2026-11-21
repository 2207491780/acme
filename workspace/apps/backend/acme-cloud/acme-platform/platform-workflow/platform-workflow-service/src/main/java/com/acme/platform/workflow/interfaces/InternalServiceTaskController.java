package com.acme.platform.workflow.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.workflow.api.dto.ServiceTaskCallbackRequest;
import com.acme.platform.workflow.application.WorkflowInternalUseCase;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部 ServiceTask 委托回调接口（确认⑤/AC-8；契约 internal
 * POST /internal/v1/workflow/service-tasks/{executionId}/callback，不经网关对外——业务服务
 * 委托完成后回调推进）：success=true → result 并入变量后推进；false → 重试计数/耗尽挂起
 * （reason=SERVICE_TASK_FAILED）。
 */
@RestController
public class InternalServiceTaskController {

    private final WorkflowInternalUseCase internalUseCase;

    public InternalServiceTaskController(WorkflowInternalUseCase internalUseCase) {
        this.internalUseCase = internalUseCase;
    }

    /** 委托回调（executionId 路径锚点 = wf_execution 主键）。 */
    @PostMapping("/internal/v1/workflow/service-tasks/{executionId}/callback")
    public Result<Long> callback(@PathVariable Long executionId,
                                 @RequestBody(required = false) ServiceTaskCallbackRequest request) {
        Long advanced = internalUseCase.serviceTaskCallback(executionId,
                request == null ? null : request.success(),
                request == null ? null : request.result(),
                request == null ? null : request.error());
        return Result.ok(advanced);
    }
}
