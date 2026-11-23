package com.acme.platform.workflow.application;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.WorkflowError;
import com.acme.platform.workflow.api.dto.TaskView;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import com.acme.platform.workflow.application.command.ServiceTaskCallbackCommand;
import com.acme.platform.workflow.application.command.ServiceTaskRetryCommand;
import com.acme.platform.workflow.application.command.StartInstanceCommand;
import com.acme.platform.workflow.application.command.TimerFireCommand;
import com.acme.platform.workflow.application.command.WorkflowPayloads;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.infrastructure.repository.WfProcessInstanceRepository;
import com.acme.platform.workflow.kernel.WorkflowCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 内部面用例（internal fire/callback——调度中心与业务委托回调的唯一受理面；不经网关对外）。
 * BPM-5 增补运行时三面（REQ-20261122-233425 确认③）：internal 启动（initiator 显式入参）、
 * 运行时详情（实例+变量+活动任务快照）、变量增量合并写（复用 VariableService）——跨服务
 * 消费面 {@code WorkflowRuntimeClient} 与单体本地适配器（LocalWorkflowRuntimeClient）共用。
 */
@Service("workflowInternalUseCase")
public class WorkflowInternalUseCase {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInternalUseCase.class);

    private final WorkflowCommandExecutor executor;
    private final TimerFireCommand timerFireCommand;
    private final ServiceTaskCallbackCommand serviceTaskCallbackCommand;
    private final ServiceTaskRetryCommand serviceTaskRetryCommand;
    private final StartInstanceCommand startInstanceCommand;
    private final InstanceUseCase instanceUseCase;
    private final VariableService variableService;
    private final WfProcessInstanceRepository instanceRepository;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public WorkflowInternalUseCase(WorkflowCommandExecutor executor,
                                   TimerFireCommand timerFireCommand,
                                   ServiceTaskCallbackCommand serviceTaskCallbackCommand,
                                   ServiceTaskRetryCommand serviceTaskRetryCommand,
                                   StartInstanceCommand startInstanceCommand,
                                   InstanceUseCase instanceUseCase,
                                   VariableService variableService,
                                   WfProcessInstanceRepository instanceRepository) {
        this.executor = executor;
        this.timerFireCommand = timerFireCommand;
        this.serviceTaskCallbackCommand = serviceTaskCallbackCommand;
        this.serviceTaskRetryCommand = serviceTaskRetryCommand;
        this.startInstanceCommand = startInstanceCommand;
        this.instanceUseCase = instanceUseCase;
        this.variableService = variableService;
        this.instanceRepository = instanceRepository;
    }

    /** internal 启动（definitionId+businessKey+variables+initiatorUserId；业务身份 sub 透传审计）。 */
    public Long start(Long definitionId, String businessKey, Map<String, Object> variables,
                      String initiatorUserId) {
        return executor.execute(startInstanceCommand,
                new WorkflowPayloads.StartInstance(definitionId, businessKey, variables, initiatorUserId));
    }

    /** 运行时详情（实例+变量 Map+活动任务快照 TaskView——internal 投影，AC-3）。 */
    public WorkflowInstanceDetailView runtimeDetail(Long instanceId) {
        var view = instanceUseCase.detail(instanceId);
        List<TaskView> tasks = view.tasks().stream().map(task -> new TaskView(task.id(),
                task.instanceId(), task.nodeId(), task.nodeName(), task.assignee(), task.status(),
                task.claimTime(), task.completeTime(), task.createTime(), view.definitionKey(),
                view.definitionName(), view.businessKey(), view.initiator())).toList();
        return new WorkflowInstanceDetailView(view.id(), view.definitionId(), view.definitionKey(),
                view.businessKey(), view.initiator(), view.status(), view.startTime(), view.endTime(),
                view.variables(), tasks);
    }

    /** 变量增量合并写（uk(instance_id,name) upsert，复用 VariableService；未知实例 404 口径同形）。 */
    public void writeVariables(Long instanceId, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return;
        }
        WfProcessInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_INSTANCE_NOT_FOUND,
                        "流程实例不存在：" + instanceId));
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            variableService.upsert(instanceId, entry.getKey(), entry.getValue(), instance.getTenantId());
        }
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
