package com.acme.platform.scheduler.kernel;

import com.acme.common.core.BizException;
import com.acme.common.core.Clock;
import com.acme.framework.trace.support.Traces;
import com.acme.platform.scheduler.SchedulerError;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.api.ExecutionTriggerType;
import com.acme.platform.scheduler.api.ExecutorStatus;
import com.acme.platform.scheduler.api.MisfirePolicy;
import com.acme.platform.scheduler.api.OneShotJobRegisterRequest;
import com.acme.platform.scheduler.api.SchedulerJobStatus;
import com.acme.platform.scheduler.api.TriggerType;
import com.acme.platform.scheduler.config.SchedulerProperties;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.acme.platform.scheduler.domain.SchJobExecution;
import com.acme.platform.scheduler.infrastructure.repository.ExecutorRegistryRepository;
import com.acme.platform.scheduler.infrastructure.repository.JobDefinitionRepository;
import com.acme.platform.scheduler.infrastructure.repository.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 调度内核编排器（REQ-20261121-071002 确认⑤终稿；设计规格 §3.1 终稿）：
 * <ul>
 *   <li>正确性事实源 = PG，执行精度 = 内存时间轮（{@link JobTimeWheel} 加速层；
 *       {@link #tick} 双轨并存：时间轮 drain + DB 兜底扫描，防时间轮/内存漂移）；</li>
 *   <li>触发幂等：部分唯一索引 uk(def_id, fire_time)（SCHEDULE/attempt=0）+
 *       同 job 串行门（存在未终态 execution 跳过本次 SCHEDULE 触发）双保险；</li>
 *   <li>misfire 两策略（{@link TriggerCalculator#resolveMisfire}）：FIRE_ONCE_NOW 补跑一次 /
 *       IGNORE 跳到下次（判界=整周期错过，单 tick 迟到一律正常触发）；</li>
 *   <li>触发后即时计算并落库 next_fire_time（确认⑤；并发撞唯一索引的重复场景同样推进，防卡死）；</li>
 *   <li>下发经 {@link JobDispatcher}（独立线程池），下发失败/回调 FAILED/巡检 TIMEOUT 走
 *       {@link RetryChain}（RETRY execution attempt+1，耗尽→终态 FAILED，告警归 JOB-4）。</li>
 * </ul>
 * tick 入口 {@link SchedulerTickJob}（ShedLock 单发），巡检入口 {@link TimeoutPatrolJob}（独立锁名）。
 */
@Component
public class SchedulerKernel implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SchedulerKernel.class);

    private final JobDefinitionRepository definitionRepository;
    private final JobExecutionRepository executionRepository;
    private final ExecutorRegistryRepository executorRegistryRepository;
    private final JobTimeWheel timeWheel;
    private final TriggerCalculator triggerCalculator;
    private final JobDispatcher dispatcher;
    private final RetryChain retryChain;
    private final ThreadPoolTaskExecutor dispatchExecutor;
    private final SchedulerProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SchedulerKernel(JobDefinitionRepository definitionRepository,
                           JobExecutionRepository executionRepository,
                           ExecutorRegistryRepository executorRegistryRepository,
                           JobTimeWheel timeWheel,
                           TriggerCalculator triggerCalculator,
                           JobDispatcher dispatcher,
                           RetryChain retryChain,
                           ThreadPoolTaskExecutor schedulerDispatchExecutor,
                           SchedulerProperties schedulerProperties,
                           JsonMapper jsonMapper,
                           Clock schedulerClock) {
        this.definitionRepository = definitionRepository;
        this.executionRepository = executionRepository;
        this.executorRegistryRepository = executorRegistryRepository;
        this.timeWheel = timeWheel;
        this.triggerCalculator = triggerCalculator;
        this.dispatcher = dispatcher;
        this.retryChain = retryChain;
        this.dispatchExecutor = schedulerDispatchExecutor;
        this.properties = schedulerProperties;
        this.jsonMapper = jsonMapper;
        this.clock = schedulerClock;
    }

    // ---------------------------------------------------------------- 启动恢复

    /**
     * 启动恢复（设计规格 §3.1：扫描 sch_job_definition(status=ENABLED) 计算 next_fire_time，
     * 时间轮覆盖窗内任务进轮；执行历史不恢复——历史是结果记录）。
     * ONCE 已触发完成（onceFireAt 已过且 next_fire 空）不再重排——防重启后一次性任务重复触发。
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Instant now = clock.now();
        List<SchJobDefinition> unscheduled = definitionRepository.findEnabledWithoutNextFire();
        int scheduled = 0;
        for (SchJobDefinition definition : unscheduled) {
            if (definition.getTriggerType() == TriggerType.ONCE
                    && definition.getOnceFireAt() != null && !definition.getOnceFireAt().isAfter(now)) {
                continue;
            }
            Instant initial = triggerCalculator.initialFireTime(definition, now);
            definition.setNextFireTime(initial);
            definitionRepository.save(definition);
            registerWheelQuietly(definition, initial, now);
            scheduled++;
        }
        List<SchJobDefinition> upcoming = definitionRepository.findUpcomingBetween(now, now.plusSeconds(3600L));
        for (SchJobDefinition definition : upcoming) {
            registerWheelQuietly(definition, definition.getNextFireTime(), now);
        }
        log.info("调度内核启动恢复完成：初始排期 {} 项，时间轮回填 {} 项", scheduled, upcoming.size());
    }

    private void registerWheelQuietly(SchJobDefinition definition, Instant fireTime, Instant now) {
        if (fireTime == null) {
            return;
        }
        if (!timeWheel.register(definition.getId(), fireTime, now)) {
            log.debug("触发点超出时间轮覆盖窗，留 DB 兜底：jobId={}, fireTime={}", definition.getId(), fireTime);
        }
    }

    /**
     * 定义变更后的时间轮重排（管理面启用/更新入口；幂等重注册）。
     */
    public void rescheduleWheel(SchJobDefinition definition) {
        Instant now = clock.now();
        timeWheel.remove(definition.getId());
        registerWheelQuietly(definition, definition.getNextFireTime(), now);
    }

    /**
     * 时间轮条目移除（管理面停用/删除入口；幂等）。
     */
    public void removeWheel(Long definitionId) {
        timeWheel.remove(definitionId);
    }

    // ---------------------------------------------------------------- tick 主循环

    /**
     * tick 主循环体（@SchedulerLock 由 SchedulerTickJob 承载——ShedLock 单发保证集群内单执行者）。
     * 三步并存：① 时间轮回填+drain（内存加速层）；② DB 兜底扫描（next_fire_time&lt;=now——
     * 时间轮漏网 / 重启恢复 / misfire 入口）；③ PENDING 恢复扫描（重试链到期下发 + 孤儿重驱动）。
     */
    public void tick() {
        Instant now = clock.now();
        // ① 时间轮
        refillWheel(now);
        Set<Long> processed = new HashSet<>();
        var wheelDue = timeWheel.advance(now);
        var dbDue = definitionRepository.findDue(now);
        var pendingDue = executionRepository.findPendingDue(now);
        log.info("TICK-STAGE now={} wheelDue={} dbDue={} pendingDue={}", now,
                wheelDue.size(), dbDue.size(), pendingDue.size());
        for (JobTimeWheel.DueEntry entry : wheelDue) {
            if (processed.add(entry.jobId())) {
                definitionRepository.findById(entry.jobId())
                        .ifPresent(definition -> triggerSchedule(definition, entry.fireTime()));
            }
        }
        // ② DB 兜底（幂等由唯一索引 + 串行门兜底，processed 去重防同 tick 双算）
        for (SchJobDefinition definition : dbDue) {
            if (processed.add(definition.getId())) {
                triggerSchedule(definition, definition.getNextFireTime());
            }
        }
        // ③ PENDING 恢复扫描（重试链到期 / 孤儿 PENDING；CAS 让位保证不与首发重复下发）
        for (SchJobExecution pending : pendingDue) {
            submitDispatchQuietly(pending.getId());
        }
    }

    /** 时间轮回填：窗口内即将到期且未入轮的定义注册进轮（每 tick 一查，窄窗低代价）。 */
    private void refillWheel(Instant now) {
        int horizon = properties.getKernel().getWheelHorizonSeconds();
        for (SchJobDefinition definition : definitionRepository.findUpcomingBetween(now,
                now.plusSeconds(horizon))) {
            if (!timeWheel.contains(definition.getId())) {
                registerWheelQuietly(definition, definition.getNextFireTime(), now);
            }
        }
    }

    // ---------------------------------------------------------------- 触发

    /**
     * SCHEDULE 触发（misfire 处置 → 串行门 → 唯一索引兜底插入 PENDING execution →
     * 推进 next_fire_time → 即时下发）。
     */
    public void triggerSchedule(SchJobDefinition definition, Instant fireTime) {
        if (fireTime == null || definition.getStatus() != SchedulerJobStatus.ENABLED) {
            return;
        }
        Instant now = clock.now();
        TriggerCalculator.MisfireDecision decision =
                triggerCalculator.resolveMisfire(definition, fireTime, now);
        if (!decision.fireNow()) {
            // IGNORE：放弃错过窗口，跳到下一个未来触发点
            advanceNextFire(definition, decision.fireTime(), now);
            return;
        }
        // 同 job 串行门（确认⑤：上一 execution 未终态不触发下一次 SCHEDULE）
        if (executionRepository.existsNonTerminalByDefinitionId(definition.getId())) {
            log.debug("串行门拦截：jobKey={} 存在未终态执行，跳过本次触发 fireTime={}",
                    definition.getJobKey(), decision.fireTime());
            advanceNextFire(definition, decision.fireTime(), now);
            return;
        }
        InsertResult result = insertScheduleExecution(definition, decision.fireTime());
        if (result.inserted()) {
            submitDispatchQuietly(result.execution().getId());
        }
        // 触发后即时计算并落库 next_fire_time（INSERTED 与 DUPLICATE 均推进——DUPLICATE 为
        // 并发双触发/崩溃恢复场景，不推进会永久卡在旧触发点）
        advanceNextFire(definition, decision.fireTime(), now);
    }

    /** 管理面手动触发（MANUAL execution；不受 SCHEDULE 唯一索引约束；next_fire 不推进）。 */
    public Long triggerManual(SchJobDefinition definition) {
        SchJobExecution execution = newExecution(definition, ExecutionTriggerType.MANUAL, clock.now());
        executionRepository.save(execution);
        submitDispatchQuietly(execution.getId());
        log.info("手动触发：jobKey={}, executionId={}", definition.getJobKey(), execution.getId());
        return execution.getId();
    }

    /**
     * 内部一次性任务注册（BPM 联动，确认④）：创建即 ENABLED 的 ONCE 定义，next_fire=onceFireAt，
     * 注册即返回（由内核在触发点下发，AC-12 实证）。
     */
    public SchJobDefinition registerOneShot(OneShotJobRegisterRequest request) {
        if (definitionRepository.findByJobKey(request.jobKey()).isPresent()) {
            throw new BizException(SchedulerError.SCHEDULER_JOB_KEY_DUPLICATE);
        }
        SchJobDefinition definition = new SchJobDefinition();
        definition.setJobKey(request.jobKey());
        definition.setAppName(request.appName());
        definition.setHandler(request.handler());
        definition.setTriggerType(TriggerType.ONCE);
        definition.setOnceFireAt(request.onceFireAt());
        definition.setParam(request.param());
        definition.setRetryPolicy(defaultRetryPolicy(request));
        definition.setShardTotal(1);
        definition.setTimeoutMs(request.timeoutMs() == null ? 60_000L : request.timeoutMs());
        definition.setMisfirePolicy(MisfirePolicy.FIRE_ONCE_NOW);
        definition.setStatus(SchedulerJobStatus.ENABLED);
        definition.setNextFireTime(request.onceFireAt());
        triggerCalculator.validate(definition);
        RetryPolicy.validate(definition.getRetryPolicy(), jsonMapper);
        definitionRepository.save(definition);
        registerWheelQuietly(definition, definition.getNextFireTime(), clock.now());
        log.info("一次性任务注册：jobKey={}, appName={}, onceFireAt={}",
                definition.getJobKey(), definition.getAppName(), definition.getOnceFireAt());
        return definition;
    }

    /** 一次性注册的重试策略缺省（可空=不重试；BPM 侧显式传参覆盖）。 */
    private String defaultRetryPolicy(OneShotJobRegisterRequest request) {
        if (request.retryMaxAttempts() == null || request.retryMaxAttempts() <= 0) {
            return null;
        }
        long interval = request.retryInitialIntervalMs() == null ? 5_000L : request.retryInitialIntervalMs();
        return "{\"maxAttempts\":" + request.retryMaxAttempts() + ",\"initialIntervalMs\":" + interval + "}";
    }

    // ---------------------------------------------------------------- 结果回调（状态 CAS）

    /**
     * 结果回调推进（确认④ CAS 终稿）：PENDING/DISPATCHED 只可推进到 RUNNING/FAILED/CANCELLED；
     * RUNNING→终态；PENDING/DISPATCHED 不可直接 SUCCESS；终态不可再推进。
     * FAILED 终态落定后续重试链（attempt+1）；SUCCESS/CANCELLED 无后续。
     */
    public SchJobExecution advanceResult(Long executionId, ExecutionState targetState,
                                         String result, String error, Long costMs) {
        SchJobExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new BizException(SchedulerError.SCHEDULER_EXECUTION_NOT_FOUND));
        if (!execution.getState().canAdvanceTo(targetState)) {
            throw new BizException(SchedulerError.SCHEDULER_EXECUTION_STATE_CONFLICT,
                    "非法状态跃迁: " + execution.getState() + " -> " + targetState);
        }
        execution.setState(targetState);
        if (result != null) {
            execution.setResult(result);
        }
        if (error != null) {
            execution.setError(error);
        }
        if (costMs != null) {
            execution.setCostMs(costMs);
        }
        executionRepository.save(execution);
        log.info("结果回调推进：executionId={}, -> {}", executionId, targetState);
        if (targetState == ExecutionState.FAILED) {
            definitionRepository.findById(execution.getDefinitionId()).ifPresent(definition ->
                    retryChain.spawnRetry(definition, execution));
        }
        return execution;
    }

    // ---------------------------------------------------------------- 超时巡检

    /**
     * 超时巡检体（独立 @Scheduled 10s + 独立锁名，由 {@link TimeoutPatrolJob} 承载调度）：
     * ① RUNNING 超 timeout_ms → TIMEOUT + 重试链（确认⑤）；
     * ② 孤儿恢复（PENDING 超下发接单窗口未推进 / DISPATCHED 接单确认丢失——进程崩溃兜底）
     *    → FAILED(DISPATCH_TIMEOUT) + 重试链；
     * ③ 执行器失联回写 OFFLINE（AC-10，30s 无心跳）。
     */
    public void patrol() {
        Instant now = clock.now();
        for (SchJobExecution execution : executionRepository.findByState(ExecutionState.RUNNING)) {
            definitionRepository.findById(execution.getDefinitionId()).ifPresent(definition -> {
                long timeoutMs = definition.getTimeoutMs() == null ? 60_000L : definition.getTimeoutMs();
                Instant startedAt = execution.getUpdatedAt() == null
                        ? execution.getFireTime() : execution.getUpdatedAt();
                if (startedAt != null && startedAt.plusMillis(timeoutMs).isBefore(now)) {
                    execution.setState(ExecutionState.TIMEOUT);
                    execution.setError(DispatchErrors.EXECUTION_TIMEOUT + ": timeout_ms=" + timeoutMs);
                    executionRepository.save(execution);
                    log.warn("超时巡检命中：executionId={}, jobKey={}, timeout_ms={}",
                            execution.getId(), execution.getJobKey(), timeoutMs);
                    retryChain.spawnRetry(definition, execution);
                }
            });
        }
        long orphanMs = properties.getDispatch().getTimeoutMs() * 2;
        for (SchJobExecution execution : executionRepository.findPendingDue(now)) {
            if (execution.getFireTime().plusMillis(orphanMs).isBefore(now)) {
                log.warn("孤儿执行恢复：executionId={} 超过接单窗口未推进，按 DISPATCH_TIMEOUT 处置",
                        execution.getId());
                dispatcher.failExecution(execution, DispatchErrors.DISPATCH_TIMEOUT,
                        "孤儿执行恢复：超过下发接单窗口未推进");
            }
        }
        for (SchJobExecution execution : executionRepository.findByState(ExecutionState.DISPATCHED)) {
            if (execution.getUpdatedAt() != null && execution.getUpdatedAt().plusMillis(orphanMs).isBefore(now)) {
                log.warn("孤儿执行恢复（DISPATCHED）：executionId={} 接单确认丢失", execution.getId());
                dispatcher.failExecution(execution, DispatchErrors.DISPATCH_TIMEOUT, "孤儿执行恢复：接单确认丢失");
            }
        }
        markStaleExecutorsOffline(now);
    }

    /** 失联执行器回写 OFFLINE（AC-10；视图层另有按阈值动态判定的双口径）。 */
    private void markStaleExecutorsOffline(Instant now) {
        Instant threshold = now.minusSeconds(properties.getExecutor().getHeartbeatStaleSeconds());
        for (var executor : executorRegistryRepository.findStale(threshold)) {
            executor.setStatus(ExecutorStatus.OFFLINE);
            executorRegistryRepository.save(executor);
        }
    }

    // ---------------------------------------------------------------- 内部细节

    private SchJobExecution newExecution(SchJobDefinition definition, ExecutionTriggerType triggerType,
                                         Instant fireTime) {
        SchJobExecution execution = new SchJobExecution();
        execution.setDefinitionId(definition.getId());
        execution.setJobKey(definition.getJobKey());
        execution.setTriggerType(triggerType);
        execution.setFireTime(fireTime);
        execution.setState(ExecutionState.PENDING);
        execution.setAttempt(0);
        execution.setTraceId(Traces.current() != null ? Traces.current().traceId() : Traces.newTraceId());
        return execution;
    }

    /** 插入结果（inserted=false 即唯一索引兜底命中）。 */
    private record InsertResult(boolean inserted, SchJobExecution execution) {
    }

    private InsertResult insertScheduleExecution(SchJobDefinition definition, Instant fireTime) {
        SchJobExecution execution = newExecution(definition, ExecutionTriggerType.SCHEDULE, fireTime);
        try {
            executionRepository.save(execution);
            return new InsertResult(true, execution);
        } catch (DuplicateKeyException ex) {
            log.debug("触发幂等命中（唯一索引）：jobKey={}, fireTime={}", definition.getJobKey(), fireTime);
            return new InsertResult(false, null);
        } catch (RuntimeException ex) {
            if (isUniqueViolation(ex)) {
                log.debug("触发幂等命中（唯一约束方言兜底）：jobKey={}, fireTime={}", definition.getJobKey(), fireTime);
                return new InsertResult(false, null);
            }
            throw ex;
        }
    }

    /** PG 唯一冲突的驱动侧识别（DuplicateKeyException 未覆盖路径的 message 兜底）。 */
    private static boolean isUniqueViolation(RuntimeException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return message.contains("duplicate key value") || message.contains("unique constraint");
    }

    /** 推进 next_fire_time（触发后即时计算落库；ONCE→null；跳过全部已过期槽位后回填时间轮）。 */
    private void advanceNextFire(SchJobDefinition definition, Instant firedSlot, Instant now) {
        Instant next = triggerCalculator.firstFutureFireTime(definition, firedSlot, now);
        definition.setNextFireTime(next);
        definitionRepository.save(definition);
        timeWheel.remove(definition.getId());
        registerWheelQuietly(definition, next, now);
    }

    private void submitDispatchQuietly(Long executionId) {
        try {
            dispatchExecutor.execute(() -> dispatcher.dispatch(executionId));
        } catch (RuntimeException ex) {
            log.error("下发任务提交失败（线程池饱和/关闭）：executionId={}, error={}", executionId, ex.getMessage());
            executionRepository.findById(executionId).ifPresent(execution ->
                    dispatcher.failExecution(execution, DispatchErrors.DISPATCH_FAILED,
                            "下发任务提交失败: " + ex.getMessage()));
        }
    }
}
