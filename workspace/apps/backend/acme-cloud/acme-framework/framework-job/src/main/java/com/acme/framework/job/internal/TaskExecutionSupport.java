package com.acme.framework.job.internal;

import com.acme.common.core.BizException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.framework.job.config.JobProperties;
import com.acme.framework.job.support.JobError;
import com.acme.framework.job.support.JobLockKeys;
import com.acme.framework.job.support.MutexMode;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskResult;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import java.util.UUID;

/**
 * 任务互斥执行与执行日志（FR-025/027/028/029，架构设计 10.2~10.4）：
 * 编程式互斥经 ShedLock {@link LockingTaskExecutor}（取舍 #10）；执行体由调用方提供
 * （切面内 {@code joinPoint.proceed()} 走代理链，10.2）；执行日志=结构化日志
 * （taskId/起止/结果/失败原因/traceId，Q-005 不落库）；日志失败不影响任务结果（FR-027 异常分支）。
 * 执行前生成 traceId 绑定 TraceContext、结束清理（14.1）。
 */
public class TaskExecutionSupport {

    private static final Logger log = LoggerFactory.getLogger("acme.job.execution");

    private final LockingTaskExecutor lockingTaskExecutor;
    private final JobLockKeys lockKeys;
    private final JobProperties properties;

    public TaskExecutionSupport(LockingTaskExecutor lockingTaskExecutor, JobLockKeys lockKeys,
                                JobProperties properties) {
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.lockKeys = lockKeys;
        this.properties = properties;
    }

    /**
     * 互斥执行（调度与手动触发同径；业务体经 proceed/代理链调用）。
     *
     * @param triggerMode true=手动触发语义（未获锁抛 TASK_RUNNING）；false=调度语义（未获锁跳过，返回 null）
     * @param body        业务执行体（切面 proceed 或触发入口调用）
     * @return 业务返回值（调度未获锁返回 null）
     * @throws BizException 锁提供方不可用（互斥语义优先，不得双跑）；triggerMode 且执行中（TASK_RUNNING）
     */
    public Object execute(TaskDefinition definition, boolean triggerMode, TaskBody body) {
        String lockName = resolveLockName(definition);
        Duration lockAtMostFor = definition.lockAtMostFor() != null
                ? definition.lockAtMostFor() : properties.getLockAtMostFor();
        Duration lockAtLeastFor = definition.lockAtLeastFor() != null
                ? definition.lockAtLeastFor() : properties.getLockAtLeastFor();
        LockConfiguration lockConfiguration =
                new LockConfiguration(Instant.now(), lockName, lockAtMostFor, lockAtLeastFor);
        Holder holder = new Holder();
        try {
            lockingTaskExecutor.executeWithLock(
                    (LockingTaskExecutor.Task) () -> invoke(definition, holder, body), lockConfiguration);
        } catch (Throwable ex) {
            if (ex instanceof BizException biz) {
                throw biz;
            }
            if (holder.startedAt != null) {
                // 业务执行已开始：失败原因归任务本身，原样抛出（执行日志已记录）
                if (ex instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("任务执行失败: " + definition.taskId(), ex);
            }
            // 锁表缺失/Redis 不可用：任务不得双跑（FR-025 异常分支），失败可观测
            throw new BizException(JobError.LOCK_PROVIDER_UNAVAILABLE, "任务互斥服务不可用", ex);
        }
        if (holder.startedAt == null) {
            if (triggerMode) {
                throw new BizException(JobError.TASK_RUNNING, "任务正在执行: " + definition.taskId());
            }
            log.info("task-skip taskId={} reason=locked", definition.taskId());
            return null;
        }
        return holder.value;
    }

    private void invoke(TaskDefinition definition, Holder holder, TaskBody body) {
        Instant startedAt = Instant.now();
        holder.startedAt = startedAt;
        TraceContext previous = TraceContext.get();
        String traceId = newTraceId();
        TraceContext.bind(new TraceContext(traceId, previous == null ? traceId : previous.spanId()));
        try {
            holder.value = body.call();
            holder.result = TaskResult.success(definition.taskId(), startedAt, Instant.now());
            writeLog(definition, holder.result, traceId);
        } catch (Throwable ex) {
            holder.result = TaskResult.failure(definition.taskId(), startedAt, Instant.now(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            writeLog(definition, holder.result, traceId);
            if (ex instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (ex instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("任务执行失败: " + definition.taskId(), ex);
        } finally {
            // 结束清理（14.1）：恢复先前 TraceContext（嵌套任务场景）
            if (previous == null) {
                TraceContext.clear();
            } else {
                TraceContext.bind(previous);
            }
        }
    }

    /** 执行日志（FR-027/AC-026，Q-005 结构化）；日志失败不影响任务结果 + ERROR 兜底。 */
    private void writeLog(TaskDefinition definition, TaskResult result, String traceId) {
        try {
            log.info("task-execution traceId={} taskId={} accessPoint={} success={} startedAt={} finishedAt={} failureReason={}",
                    traceId, definition.taskId(), definition.accessPoint(), result.success(),
                    result.startedAt(), result.finishedAt(), result.failureReason());
        } catch (RuntimeException ex) {
            log.error("任务执行日志产生失败（不影响任务结果，FR-027）: {}", ex.getMessage());
        }
    }

    /** 锁 key（FR-029/AC-027）：TENANT 声明且有租户语境→带租户段；否则全局（可判定）。 */
    String resolveLockName(TaskDefinition definition) {
        String tenantId = null;
        if (definition.mutexMode() == MutexMode.TENANT) {
            TenantContext tenant = TenantContext.get();
            tenantId = tenant == null ? null : tenant.tenantId();
            if (tenantId == null || tenantId.isBlank()) {
                log.warn("MutexMode.TENANT 但无租户语境，退化全局锁（FR-029 异常分支）: taskId={}", definition.taskId());
                tenantId = null;
            }
        }
        return lockKeys.build(definition.taskId(), tenantId);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class Holder {
        private Instant startedAt;
        private TaskResult result;
        private Object value;
    }
}
