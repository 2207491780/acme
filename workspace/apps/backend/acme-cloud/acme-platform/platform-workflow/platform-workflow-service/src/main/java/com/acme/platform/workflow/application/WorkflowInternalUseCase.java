package com.acme.platform.workflow.application;

import com.acme.platform.workflow.application.command.ServiceTaskCallbackCommand;
import com.acme.platform.workflow.application.command.ServiceTaskRetryCommand;
import com.acme.platform.workflow.application.command.TimerFireCommand;
import com.acme.platform.workflow.application.command.WorkflowPayloads;
import com.acme.platform.workflow.kernel.WorkflowCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 内部面用例（internal fire/callback——调度中心与业务委托回调的唯一受理面；不经网关对外）。
 */
@Service("workflowInternalUseCase")
public class WorkflowInternalUseCase {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInternalUseCase.class);

    private final WorkflowCommandExecutor executor;
    private final TimerFireCommand timerFireCommand;
    private final ServiceTaskCallbackCommand serviceTaskCallbackCommand;
    private final ServiceTaskRetryCommand serviceTaskRetryCommand;

    public WorkflowInternalUseCase(WorkflowCommandExecutor executor,
                                   TimerFireCommand timerFireCommand,
                                   ServiceTaskCallbackCommand serviceTaskCallbackCommand,
                                   ServiceTaskRetryCommand serviceTaskRetryCommand) {
        this.executor = executor;
        this.timerFireCommand = timerFireCommand;
        this.serviceTaskCallbackCommand = serviceTaskCallbackCommand;
        this.serviceTaskRetryCommand = serviceTaskRetryCommand;
    }

    /** 定时触发（CAS PENDING→FIRED；重复 fire → 409 信封）。 */
    public Long fireTimer(Long timerJobId, String schedulerExecutionId) {
        return executor.execute(timerFireCommand,
                new WorkflowPayloads.TimerFire(timerJobId, schedulerExecutionId));
    }

    /** 本地兜底触发（异常降级为告警日志——兜底通道不反噬主流程；命令内行租户绑定，finally 清理线程态）。 */
    public void fireTimerQuietly(Long timerJobId) {
        try {
            fireTimer(timerJobId, null);
        } catch (RuntimeException ex) {
            log.warn("本地兜底触发失败（CAS 幂等/状态面拒绝属预期）：timerJobId={}", timerJobId, ex);
        } finally {
            com.acme.common.context.TenantContext.clear();
        }
    }

    /** ServiceTask 委托回调（success=true 推进 / false 重试计数）。 */
    public Long serviceTaskCallback(Long executionId, Boolean success,
                                    java.util.Map<String, Object> result, String error) {
        return executor.execute(serviceTaskCallbackCommand,
                new WorkflowPayloads.ServiceTaskCallback(executionId, success, result, error));
    }

    /** ServiceTask 重试下发（退避窗口竞态静默退出；命令内行租户绑定，finally 清理线程态）。 */
    public void serviceTaskRetry(Long executionId, int attempt) {
        try {
            executor.execute(serviceTaskRetryCommand,
                    new WorkflowPayloads.ServiceTaskRetry(executionId, attempt));
        } catch (ServiceTaskRetryCommand.StaleRetryException ex) {
            log.info("ServiceTask 重试失效（退避窗口内状态已变化）：executionId={}", executionId);
        } finally {
            com.acme.common.context.TenantContext.clear();
        }
    }
}
