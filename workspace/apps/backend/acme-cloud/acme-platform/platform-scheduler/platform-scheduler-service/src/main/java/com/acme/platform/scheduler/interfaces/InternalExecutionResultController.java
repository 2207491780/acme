package com.acme.platform.scheduler.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.scheduler.api.ExecutionResultReport;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.application.ExecutionCallbackUseCase;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行结果回调内部接口（设计规格 §4：执行完成后执行器异步上报；不经网关对外，openapi 不登记）。
 * 状态 CAS 口径（确认④终稿）见 ExecutionState#canAdvanceTo：
 * PENDING/DISPATCHED→RUNNING/FAILED/CANCELLED；RUNNING→终态；PENDING/DISPATCHED 不可直接 SUCCESS；
 * 终态不可再推进（非法跃迁 → SCHEDULER_EXECUTION_STATE_CONFLICT 409 信封）。
 */
@RestController
public class InternalExecutionResultController {

    private final ExecutionCallbackUseCase callbackUseCase;

    public InternalExecutionResultController(ExecutionCallbackUseCase callbackUseCase) {
        this.callbackUseCase = callbackUseCase;
    }

    /** 结果上报（state=RUNNING 开跑可选；SUCCESS/FAILED 终态必须——FAILED 触发重试链）。 */
    @PostMapping("/internal/v1/scheduler/executions/{executionId}/result")
    public Result<Void> result(@PathVariable Long executionId,
                               @RequestBody ExecutionResultReport report) {
        callbackUseCase.report(executionId, report);
        return Result.ok(null);
    }
}
