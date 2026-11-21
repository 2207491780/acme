package com.acme.platform.workflow.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.workflow.api.dto.TimerFireRequest;
import com.acme.platform.workflow.application.WorkflowInternalUseCase;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部定时触发接口（确认⑥；契约 internal POST /internal/v1/workflow/timers/{timerJobId}/fire，
 * 不经网关对外——调度中心回调直连）：CAS PENDING→FIRED 幂等（重复 fire → 02-0002 状态冲突信封
 * 拒绝）；中断挂接节点 → 边界流出分支推进（AC-9）。
 */
@RestController
public class InternalTimerController {

    private final WorkflowInternalUseCase internalUseCase;

    public InternalTimerController(WorkflowInternalUseCase internalUseCase) {
        this.internalUseCase = internalUseCase;
    }

    /** 定时触发（CAS 幂等；timerJobId 路径锚点 = wf_timer_job 主键）。 */
    @PostMapping("/internal/v1/workflow/timers/{timerJobId}/fire")
    public Result<Long> fire(@PathVariable Long timerJobId,
                             @RequestBody(required = false) TimerFireRequest request) {
        Long fired = internalUseCase.fireTimer(timerJobId,
                request == null ? null : request.schedulerExecutionId());
        return Result.ok(fired);
    }
}
