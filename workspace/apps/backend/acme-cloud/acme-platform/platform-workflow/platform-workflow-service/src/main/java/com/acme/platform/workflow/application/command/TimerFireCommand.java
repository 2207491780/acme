package com.acme.platform.workflow.application.command;

import com.acme.common.core.BizException;
import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.application.WorkflowCommandSupport;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTimerJob;
import com.acme.platform.workflow.engine.EngineRuntime;
import com.acme.platform.workflow.engine.ExecutionEngine;
import com.acme.platform.workflow.infrastructure.repository.WfTimerJobRepository;
import com.acme.platform.workflow.kernel.CommandContext;
import com.acme.platform.workflow.kernel.WorkflowCommand;
import com.acme.platform.workflow.WorkflowError;
import org.springframework.stereotype.Component;

/**
 * 定时器触发命令（确认⑥；契约 internal POST /timers/{timerJobId}/fire）：CAS PENDING→FIRED
 * 才生效（重复 fire → 409 拒绝；AC-9）；实例非 RUNNING（挂起/终态）拒绝；中断挂接节点 →
 * 边界流出分支推进（引擎 fireTimer 承载）。
 */
@Component
public class TimerFireCommand implements WorkflowCommand<Long> {

    private final WorkflowCommandSupport support;
    private final ExecutionEngine executionEngine;
    private final WfTimerJobRepository timerJobRepository;

    public TimerFireCommand(WorkflowCommandSupport support, ExecutionEngine executionEngine,
                            WfTimerJobRepository timerJobRepository) {
        this.support = support;
        this.executionEngine = executionEngine;
        this.timerJobRepository = timerJobRepository;
    }

    private WfTimerJob timerJob;
    private WfProcessInstance instance;
    private WfExecution execution;
    private CompiledProcessModel model;
    private EngineRuntime runtime;

    @Override
    public void load(CommandContext context) {
        WorkflowPayloads.TimerFire payload = context.payload(WorkflowPayloads.TimerFire.class);
        // fire 通道（调度中心回调 tenantId=null / 本地兜底线程无上下文）：行全局直读 + 行租户绑定
        // 命令线程（HTTP 路径由 InternalTokenAuthFilter finally 清理；兜底路径由用例 finally 清理）
        timerJob = timerJobRepository.findByIdGlobal(payload.timerJobId())
                .orElseThrow(() -> new BizException(WorkflowError.WORKFLOW_INSTANCE_NOT_FOUND,
                        "定时任务不存在：" + payload.timerJobId()));
        com.acme.common.context.TenantContext.bind(
                new com.acme.common.context.TenantContext(timerJob.getTenantId()));
        instance = support.loadInstance(timerJob.getInstanceId());
        execution = support.loadExecution(timerJob.getExecutionId());
        model = support.loadModel(instance.getDefinitionId());
        runtime = support.buildRuntime(context, model, instance, instance.getTenantId());
    }

    @Override
    public void validate(CommandContext context) {
        // CAS 前置（确认⑥：PENDING→FIRED 才生效；重复 fire 拒绝——乐观锁承载并发双 fire 收敛单胜）
        if (!WfTimerJob.STATUS_PENDING.equals(timerJob.getStatus())) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "定时器已离开 PENDING（重复 fire 拒绝）：" + timerJob.getId());
        }
        if (instance.getStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "实例非 RUNNING 态，拒绝定时触发：" + instance.getStatus());
        }
        if (!Boolean.TRUE.equals(execution.getActive())) {
            throw new BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "挂接执行已不活跃，定时触发失效：" + execution.getId());
        }
    }

    @Override
    public Long execute(CommandContext context) {
        executionEngine.fireTimer(runtime, timerJob);
        executionEngine.drive(runtime);
        return timerJob.getId();
    }
}
