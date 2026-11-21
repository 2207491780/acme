package com.acme.framework.job.executor;

import com.acme.framework.job.config.JobProperties;
import com.acme.framework.job.support.JobExecutionContext;
import com.acme.framework.job.support.JobExecutionTriggers;
import com.acme.framework.job.support.JobHandlerDefinition;
import com.acme.framework.job.support.JobHandlerRegistry;
import com.acme.framework.job.support.TaskTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指令接收端点（REQ-20261121-200724 确认⑤）：POST /internal/v1/job/executions/{executionId}/run
 * （路径/字段按 JOB-1 契约面，X-Acme-Token 校验对齐现有 internal 面——framework-security
 * InternalTokenAuthFilter 对 /internal/** 统一拦截）。接单语义：
 * <ul>
 *   <li>执行器未注册（降级/未就绪）或 jobKey 未知 → accepted=false（调度侧 DISPATCH_REJECTED 走重试链）；</li>
 *   <li>接单后异步执行（接单超时 10s 口径，方法体不占下发请求线程）——经 {@link TaskTrigger} 代理链
 *       走 {@code @JobHandler} 切面（互斥 + 执行日志，双跑互斥由 ShedLock 保证）；</li>
 *   <li>执行完成异步上报 result（SUCCESS/FAILED + error + 耗时，落 sch_job_execution 由调度侧 CAS）；</li>
 *   <li>超时（{@code @JobHandler} timeoutMs）→ TIMEOUT 上报 + 方法线程中断尽力（AC-3）。</li>
 * </ul>
 */
@RestController
public class JobExecutorRunController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobExecutorRunController.class);

    /** 下发接单端点路径（JOB-1 契约面，与调度侧 JobDispatcher.EXECUTOR_RUN_PATH 一致）。 */
    public static final String RUN_PATH = "/internal/v1/job/executions/{executionId}/run";

    /** scheduler-api 下发指令类型名（反射透传用；框架层不编译依赖 platform 模块）。 */
    private static final String SCHEDULER_COMMAND_NAME =
            "com.acme.platform.scheduler.api.JobDispatchCommand";

    /** scheduler-api 分片上下文类型名。 */
    private static final String SCHEDULER_SHARD_NAME = "com.acme.platform.scheduler.api.ShardContext";

    private final ExecutorLifecycle lifecycle;
    private final JobHandlerRegistry handlerRegistry;
    private final TaskTrigger taskTrigger;
    private final SchedulerClient schedulerClient;
    private final JobProperties properties;
    private final java.util.concurrent.ExecutorService runPool;
    private final ScheduledExecutorService timeoutWatchdog;

    public JobExecutorRunController(ExecutorLifecycle lifecycle, JobHandlerRegistry handlerRegistry,
                                    TaskTrigger taskTrigger, SchedulerClient schedulerClient,
                                    JobProperties properties) {
        this.lifecycle = lifecycle;
        this.handlerRegistry = handlerRegistry;
        this.taskTrigger = taskTrigger;
        this.schedulerClient = schedulerClient;
        this.properties = properties;
        this.runPool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(1, properties.getExecutor().getRunPoolSize()), daemonFactory("acme-job-run-"));
        this.timeoutWatchdog = Executors.newSingleThreadScheduledExecutor(daemonFactory("acme-job-timeout-"));
    }

    /** 接单（200+accepted=true→执行→result 上报；未知 jobKey/未注册→accepted=false）。 */
    @PostMapping(RUN_PATH)
    public AcceptView run(@PathVariable("executionId") Long executionId,
                          @RequestBody DispatchCommand command) {
        if (!lifecycle.accepting()) {
            return AcceptView.rejected();
        }
        String key = command.jobKey() != null && !command.jobKey().isBlank()
                ? command.jobKey() : command.handler();
        JobHandlerDefinition handler = key == null ? null : handlerRegistry.find(key).orElse(null);
        if (handler == null) {
            // 缺 handler → DISPATCH_REJECTED（确认⑤）
            log.warn("拒绝接单（未知 jobKey）：executionId={}, jobKey={}", executionId, key);
            return AcceptView.rejected();
        }
        DispatchCommand.Shard shard = command.shardContext() == null
                ? DispatchCommand.Shard.single() : command.shardContext();
        JobExecutionContext context = new JobExecutionContext(executionId, handler.jobKey(),
                command.handler(), command.param(), command.fireTime(), command.traceId(),
                command.attempt(), shard.index(), shard.total(), JobExecutionTriggers.SCHEDULER_DISPATCH);
        submitExecution(executionId, handler, context);
        return AcceptView.ok();
    }

    /** 异步执行 + 超时看门狗 + 终态上报（单次执行恰一份终态上报，reported 竞争收口）。 */
    private void submitExecution(Long executionId, JobHandlerDefinition handler, JobExecutionContext context) {
        Instant startedAt = Instant.now();
        AtomicBoolean reported = new AtomicBoolean(false);
        Future<?> future = runPool.submit(() -> {
            JobExecutionContext.bind(context);
            try {
                taskTrigger.trigger(handler.jobKey(), resolveArgs(handler, context));
                report(executionId, ResultReport.STATE_SUCCESS, null, null, startedAt, reported);
            } catch (Throwable ex) {
                report(executionId, ResultReport.STATE_FAILED, null,
                        ex.getClass().getSimpleName() + ": " + ex.getMessage(), startedAt, reported);
            } finally {
                JobExecutionContext.clear();
            }
        });
        timeoutWatchdog.schedule(() -> {
            if (reported.compareAndSet(false, true)) {
                // 超时（确认⑤）：TIMEOUT 上报 + 方法线程中断尽力（AC-3）
                future.cancel(true);
                schedulerClient.reportResult(executionId, new ResultReport(ResultReport.STATE_TIMEOUT, null,
                        "TIMEOUT: 超过 timeoutMs=" + handler.timeoutMs() + "ms（线程中断已尽力）",
                        Duration.between(startedAt, Instant.now()).toMillis()));
            }
        }, handler.timeoutMs(), TimeUnit.MILLISECONDS);
    }

    /** 终态上报（上报失败仅告警——调度侧重试链/超时巡检兜底，确认⑤）。 */
    private void report(Long executionId, String state, String result, String error,
                        Instant startedAt, AtomicBoolean reported) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        try {
            schedulerClient.reportResult(executionId, new ResultReport(state, result, error,
                    Duration.between(startedAt, Instant.now()).toMillis()));
        } catch (RuntimeException ex) {
            log.warn("执行结果上报失败（调度侧重试链/巡检兜底）: executionId={}, state={}, error={}",
                    executionId, state, ex.getMessage());
        }
    }

    /**
     * 方法实参按类型解析（确认⑤ attempt/shard 透传）：String→触发来源（下发恒 SCHEDULER_DISPATCH）；
     * {@link JobExecutionContext}→SDK 下发上下文；platform-scheduler-api JobDispatchCommand→完整指令
     * 透传（反射构造，框架层不编译依赖 platform 模块，分层 BR-001；类路径缺席时显式失败）。
     * 其余类型显式失败（FAILED 上报）。
     */
    private static Object[] resolveArgs(JobHandlerDefinition handler, JobExecutionContext context) {
        Class<?>[] parameterTypes = handler.method().getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == String.class) {
                args[i] = JobExecutionTriggers.SCHEDULER_DISPATCH;
            } else if (parameterTypes[i] == JobExecutionContext.class) {
                args[i] = context;
            } else if (SCHEDULER_COMMAND_NAME.equals(parameterTypes[i].getName())) {
                args[i] = newSchedulerCommand(context, parameterTypes[i]);
            } else {
                throw new IllegalArgumentException("不支持的 @JobHandler 参数类型: "
                        + parameterTypes[i].getName() + " (" + handler.jobKey() + ")");
            }
        }
        return args;
    }

    /** scheduler-api 下发指令镜像（框架层以反射构造，不引入 platform 编译依赖）。 */
    private static Object newSchedulerCommand(JobExecutionContext context, Class<?> commandClass) {
        try {
            Class<?> shardClass = Class.forName(SCHEDULER_SHARD_NAME, true, commandClass.getClassLoader());
            Object shard = shardClass.getConstructor(int.class, int.class)
                    .newInstance(context.shardIndex(), context.shardTotal());
            return commandClass.getConstructor(String.class, String.class, String.class,
                            shardClass, java.time.Instant.class, String.class, int.class)
                    .newInstance(context.jobKey(), context.handler(), context.param(), shard,
                            context.fireTime(), context.traceId(), context.attempt());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("构造 scheduler 下发指令失败（字段面以 JOB-1 契约为准）", ex);
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void destroy() {
        runPool.shutdownNow();
        timeoutWatchdog.shutdownNow();
    }
}
