package com.acme.platform.scheduler.application;

import com.acme.common.core.BizException;
import com.acme.platform.scheduler.SchedulerError;
import com.acme.platform.scheduler.api.ExecutionResultReport;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.kernel.SchedulerKernel;
import org.springframework.stereotype.Service;

/**
 * 结果回调用例（确认④：结果异步回调推进状态；REQ-20261122-150756 确认⑤开放 TIMEOUT 接收）：
 * 回调 state 允许 RUNNING（开跑上报，可选）/ SUCCESS / FAILED（终态，必须）/ TIMEOUT（终态，
 * SDK watchdog 先报场景）；其余取值（PENDING/DISPATCHED/CANCELLED）直接拒绝——
 * CANCELLED 归管理面，回调通道不承载。TIMEOUT 终态不可再推进天然幂等（巡检与回调先到先得）。
 */
@Service
public class ExecutionCallbackUseCase {

    private final SchedulerKernel kernel;

    public ExecutionCallbackUseCase(SchedulerKernel kernel) {
        this.kernel = kernel;
    }

    /**
     * 结果上报推进（CAS 终稿见 ExecutionState#canAdvanceTo；FAILED/TIMEOUT 落定后续重试链由内核承载）。
     */
    public void report(Long executionId, ExecutionResultReport report) {
        ExecutionState target = report.state();
        if (target != ExecutionState.RUNNING && target != ExecutionState.SUCCESS
                && target != ExecutionState.FAILED && target != ExecutionState.TIMEOUT) {
            throw new BizException(SchedulerError.SCHEDULER_EXECUTION_STATE_CONFLICT,
                    "回调状态仅允许 RUNNING/SUCCESS/FAILED/TIMEOUT: " + target);
        }
        kernel.advanceResult(executionId, target, report.result(), report.error(), report.costMs());
    }
}
