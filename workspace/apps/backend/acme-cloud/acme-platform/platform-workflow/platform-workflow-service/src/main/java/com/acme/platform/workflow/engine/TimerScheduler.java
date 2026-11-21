package com.acme.platform.workflow.engine;

import com.acme.common.util.IdGenerator;
import com.acme.common.util.SnowflakeIdGenerator;
import com.acme.framework.flex.config.FlexProperties;
import com.acme.platform.workflow.compile.CompiledElement;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfTimerJob;
import com.acme.platform.workflow.infrastructure.client.SchedulerRegistrationClient;
import com.acme.platform.workflow.infrastructure.repository.WfTimerJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时联动编排（确认⑥终稿：注册调度中心 ONCE + 本地 ScheduledExecutorService 兜底 + 注册失败
 * 重试 + fire 回调 CAS 幂等 + 注销）。
 * <ul>
 *   <li>注册：timer 落库（PENDING，预分配雪花 id→jobKey=wf-timer:{id}）后 AFTER_COMMIT 调
 *       SchedulerRegistrationClient.register（不可达→入本地重试注册表，成功后移除）；</li>
 *   <li>兜底：本地 ScheduledExecutorService 按 due_at 排期 fire（与调度中心回调共用同一命令，
 *       CAS PENDING→FIRED 保证双通道幂等）；</li>
 *   <li>注销：任务完成/驳回离开/实例终止 → timer CANCELLED + AFTER_COMMIT 注销调度中心 job
 *       （失败仅告警——本地兜底条目同步移除，不阻断清理）。</li>
 * </ul>
 */
@Component
public class TimerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimerScheduler.class);

    /** jobKey 前缀（设计规格 §6：jobKey=wf-timer:{timerJobId}）。 */
    public static final String JOB_KEY_PREFIX = "wf-timer:";

    /** 下发目标应用（设计规格 §6：appName=platform-workflow）。 */
    public static final String APP_NAME = "platform-workflow";

    /** 下发 handler 标识（BPM-6 执行器路由键，协议终稿见交付报告「定时联动协议终稿」）。 */
    public static final String HANDLER = "workflow-timer-fire";

    private final WfTimerJobRepository timerJobRepository;
    private final SchedulerRegistrationClient registrationClient;
    private final WorkflowPropertiesBridge properties;
    private final ScheduledExecutorService fallbackExecutor;
    private final Map<Long, ScheduledFuture<?>> localFallbacks = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> serviceTaskRetries = new ConcurrentHashMap<>();
    private final Map<String, Instant> pendingRegistrations = new ConcurrentHashMap<>();
    private volatile IdGenerator idGenerator;

    /** 引擎统一时钟（FR-019——BPM-1 round-1 修复⑤：兜底排期/到期扫描与 due_at 同源取时，
     *  消除「排期用系统墙钟、due_at 用注入时钟」的错配导致的 delay≤0 立即触发）。 */
    private final com.acme.common.core.Clock clock;

    public TimerScheduler(WfTimerJobRepository timerJobRepository,
                          SchedulerRegistrationClient registrationClient,
                          WorkflowPropertiesBridge properties,
                          ObjectProvider<IdGenerator> idGeneratorProvider,
                          FlexProperties flexProperties,
                          com.acme.common.core.Clock workflowClock) {
        this.timerJobRepository = timerJobRepository;
        this.registrationClient = registrationClient;
        this.properties = properties;
        this.clock = workflowClock;
        this.idGenerator = idGeneratorProvider.getIfAvailable();
        if (this.idGenerator == null) {
            var id = flexProperties.getId();
            if (id.getWorkerId() == null) {
                throw new IllegalStateException("定时器预分配雪花 id 缺 worker-id 配置（12.3.1）");
            }
            this.idGenerator = new SnowflakeIdGenerator(id.getWorkerId(), id.getDatacenterId());
        }
        this.fallbackExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "workflow-timer-fallback");
            thread.setDaemon(true);
            return thread;
        });
        // 注册失败重试（确认⑥：后台重试注册，成功后移除本地条目）
        this.fallbackExecutor.scheduleWithFixedDelay(this::retryPendingRegistrations,
                properties.timer().getRetryRegisterIntervalMs(),
                properties.timer().getRetryRegisterIntervalMs(), TimeUnit.MILLISECONDS);
        // 本地兜底扫描兜真（due_at 已过未触发条目按 CAS 幂等补触发）
        this.fallbackExecutor.scheduleWithFixedDelay(this::scanOverdueTimers,
                properties.timer().getFallbackPollMs(),
                properties.timer().getFallbackPollMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * 到达挂接节点时建 timer（预分配 id→jobKey；PENDING 落库 + 本地兜底排期 + AFTER_COMMIT
     * 注册调度中心）。
     */
    public void scheduleBoundaryTimer(EngineRuntime runtime, WfExecution execution,
                                      CompiledElement boundaryEvent, Instant dueAt) {
        WfTimerJob timerJob = new WfTimerJob();
        timerJob.setId(idGenerator.nextId());
        timerJob.setTenantId(runtime.tenantId());
        timerJob.setExecutionId(execution.getId());
        timerJob.setNodeId(boundaryEvent.attachedTo());
        timerJob.setBoundaryEventId(boundaryEvent.id());
        timerJob.setDueAt(dueAt);
        timerJob.setStatus(WfTimerJob.STATUS_PENDING);
        timerJob.setSchedulerJobKey(jobKey(timerJob.getId()));
        // instance_id 兜底回填（修复⑧同面）——实例行先行落库后此处恒可取值
        runtime.trackForBackfill(timerJob);
        runtime.persist(() -> {
            if (timerJob.getInstanceId() == null) {
                timerJob.setInstanceId(runtime.instance().getId());
            }
            if (timerJob.getTenantId() == null) {
                timerJob.setTenantId(runtime.tenantId());
            }
            timerJobRepository.save(timerJob);
        });
        scheduleLocalFallback(timerJob);
        registerSchedulerJobAfterCommit(timerJob);
    }

    /** 节点离开/任务办结注销（PENDING→CANCELLED + 本地兜底移除 + AFTER_COMMIT 注销调度中心）。 */
    public void cancelTimersForNode(EngineRuntime runtime, String hostNodeId) {
        for (WfTimerJob timerJob : timerJobRepository
                .findPendingByInstanceIdAndNodeId(runtime.instance().getId(), hostNodeId)) {
            cancelTimerJob(runtime, timerJob);
        }
    }

    /** 实例终止注销全集。 */
    public void cancelAllForInstance(EngineRuntime runtime) {
        for (WfTimerJob timerJob : timerJobRepository
                .findPendingByInstanceId(runtime.instance().getId())) {
            cancelTimerJob(runtime, timerJob);
        }
    }

    /** 注销单条 timer（实体置 CANCELLED 落库 + 本地兜底移除 + AFTER_COMMIT 注销）。 */
    private void cancelTimerJob(EngineRuntime runtime, WfTimerJob timerJob) {
        timerJob.setStatus(WfTimerJob.STATUS_CANCELLED);
        ScheduledFuture<?> future = localFallbacks.remove(timerJob.getId());
        if (future != null) {
            future.cancel(false);
        }
        runtime.persist(() -> timerJobRepository.save(timerJob));
        unregisterSchedulerJobAfterCommit(timerJob.getSchedulerJobKey());
    }

    /** jobKey 组合（设计规格 §6 终稿）。 */
    public static String jobKey(Long timerJobId) {
        return JOB_KEY_PREFIX + timerJobId;
    }

    /** 本地兜底排期（到期触发 TimerFireCommand——与调度中心回调共用 CAS 幂等命令）。 */
    private void scheduleLocalFallback(WfTimerJob timerJob) {
        long delayMs = Math.max(0L, Duration.between(clock.now(), timerJob.getDueAt()).toMillis());
        ScheduledFuture<?> future = fallbackExecutor.schedule(() -> {
            localFallbacks.remove(timerJob.getId());
            properties.fireTimer(timerJob.getId(), null);
        }, delayMs, TimeUnit.MILLISECONDS);
        localFallbacks.put(timerJob.getId(), future);
    }

    /** ServiceTask 重试排期（确认⑤：失败按 acme:retryBackoffMs 退避重发，耗尽挂起由命令面裁决）。 */
    public void scheduleServiceTaskRetry(Long executionId, int nextAttempt, long delayMs) {
        ScheduledFuture<?> future = fallbackExecutor.schedule(() -> {
            serviceTaskRetries.remove(executionId);
            properties.dispatchServiceTaskRetry(executionId, nextAttempt);
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        serviceTaskRetries.put(executionId, future);
    }

    /** AFTER_COMMIT 注册调度中心（注册失败→入本地重试注册表；同命令内已注销的 timer 跳过注册）。 */
    private void registerSchedulerJobAfterCommit(WfTimerJob timerJob) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            registerSchedulerJob(timerJob);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!WfTimerJob.STATUS_CANCELLED.equals(timerJob.getStatus())) {
                    registerSchedulerJob(timerJob);
                }
            }
        });
    }

    private void registerSchedulerJob(WfTimerJob timerJob) {
        try {
            registrationClient.register(timerJob.getSchedulerJobKey(), timerJob.getDueAt(),
                    String.valueOf(timerJob.getId()));
            pendingRegistrations.remove(timerJob.getSchedulerJobKey());
        } catch (RuntimeException ex) {
            log.warn("调度中心注册失败（入本地重试注册表，本地兜底已排期）：jobKey={}",
                    timerJob.getSchedulerJobKey(), ex);
            pendingRegistrations.put(timerJob.getSchedulerJobKey(), timerJob.getDueAt());
        }
    }

    /** AFTER_COMMIT 注销调度中心（失败仅告警）。 */
    private void unregisterSchedulerJobAfterCommit(String jobKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            unregisterSchedulerJob(jobKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                unregisterSchedulerJob(jobKey);
            }
        });
    }

    private void unregisterSchedulerJob(String jobKey) {
        pendingRegistrations.remove(jobKey);
        try {
            registrationClient.unregister(jobKey);
        } catch (RuntimeException ex) {
            log.warn("调度中心注销失败（不阻断本地清理；BPM-6 前由 scheduler 侧补齐 DELETE 端点）：jobKey={}",
                    jobKey, ex);
        }
    }

    /** 后台重试注册（确认⑥：成功后移除本地）。 */
    private void retryPendingRegistrations() {
        if (pendingRegistrations.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Instant> entry : pendingRegistrations.entrySet()) {
            String jobKey = entry.getKey();
            Instant dueAt = entry.getValue();
            if (clock.now().isAfter(dueAt)) {
                // 已过触发时刻：本地兜底已触发面兜真，不再注册
                pendingRegistrations.remove(jobKey);
                continue;
            }
            try {
                String timerJobId = jobKey.substring(JOB_KEY_PREFIX.length());
                registrationClient.register(jobKey, dueAt, timerJobId);
                pendingRegistrations.remove(jobKey);
                log.info("调度中心补注册成功：jobKey={}", jobKey);
            } catch (RuntimeException ex) {
                // 保留条目下轮重试
            }
        }
    }

    /** 兜底扫描兜真（due_at 已过的 PENDING timer 补触发；CAS 幂等防双通道重复）。 */
    private void scanOverdueTimers() {
        try {
            for (WfTimerJob timerJob : timerJobRepository.findPendingDue(clock.now())) {
                if (localFallbacks.containsKey(timerJob.getId())) {
                    continue;
                }
                properties.fireTimer(timerJob.getId(), null);
            }
        } catch (RuntimeException ex) {
            log.warn("本地兜底扫描失败（下轮重试）", ex);
        }
    }

    /**
     * 配置桥（避免循环依赖：fire/重试命令由 Spring 容器装配，经桥回调；桥实现由
     * WorkflowEngineConfiguration 装配）。
     */
    public interface WorkflowPropertiesBridge {

        /** 触发定时（timerJobId 锚点；schedulerExecutionId 可空=本地兜底）。 */
        void fireTimer(Long timerJobId, String schedulerExecutionId);

        /** ServiceTask 重试下发（executionId 锚点 + 重试轮次）。 */
        void dispatchServiceTaskRetry(Long executionId, int attempt);

        /** 定时配置域。 */
        com.acme.platform.workflow.config.WorkflowProperties.Timer timer();
    }
}
