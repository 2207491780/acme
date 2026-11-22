package com.acme.platform.scheduler.kernel;

import com.acme.common.core.Clock;
import com.acme.framework.trace.support.Traces;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.acme.platform.scheduler.domain.SchJobExecution;
import com.acme.platform.scheduler.infrastructure.repository.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * 重试链（确认⑤：下发失败 / 执行回调 FAILED / 超时巡检 TIMEOUT 统一按 retry_policy 生成
 * RETRY execution（attempt+1）；maxAttempts 耗尽→FAILED 终态 + 调度告警（JOB-4 确认④））。
 * 重试调度口径：RETRY execution 以 PENDING 落库、fire_time=now+退避间隔（FIXED 恒定 /
 * EXPONENTIAL 递增±10% jitter，REQ-20261122-150756 确认②），
 * 由内核 tick 的 PENDING 恢复扫描到期下发（重试链与首发统一恢复口径，进程重启不丢重试）。
 */
@Component
public class RetryChain {

    private static final Logger log = LoggerFactory.getLogger(RetryChain.class);

    private final JobExecutionRepository executionRepository;
    private final SchedulerAlertNotifier alertNotifier;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public RetryChain(JobExecutionRepository executionRepository,
                      SchedulerAlertNotifier alertNotifier,
                      JsonMapper jsonMapper,
                      Clock schedulerClock) {
        this.executionRepository = executionRepository;
        this.alertNotifier = alertNotifier;
        this.jsonMapper = jsonMapper;
        this.clock = schedulerClock;
    }

    /**
     * 依策略续链（失败源 execution 已终态 FAILED/TIMEOUT）：
     * 配额未尽→插入 RETRY execution（attempt+1，PENDING，fire_time=now+退避间隔）；
     * 耗尽→FAILED 终态落定 + 调度告警（REQ-20261122-150756 确认④触发点一，5 分钟窗去重）。
     */
    public void spawnRetry(SchJobDefinition definition, SchJobExecution failedExecution) {
        int attempt = failedExecution.getAttempt() == null ? 0 : failedExecution.getAttempt();
        RetryPolicy policy;
        try {
            policy = RetryPolicy.parse(definition.getRetryPolicy(), jsonMapper);
        } catch (RuntimeException ex) {
            // 策略解析失败按不重试兜底（配置缺陷不放大故障；创建/更新入口已前置校验）
            log.warn("重试策略解析失败，按不重试兜底：jobKey={}, error={}", definition.getJobKey(), ex.getMessage());
            return;
        }
        if (!policy.canRetry(attempt)) {
            log.warn("重试链耗尽（FAILED 终态）：jobKey={}, attempt={}, maxAttempts={}, lastError={}",
                    definition.getJobKey(), attempt, policy.maxAttempts(), failedExecution.getError());
            // 告警口径（REQ-20261122-150756 确认④触发点一）：「重试耗尽」语义=曾配置重试且消费殆尽；
            // maxAttempts=0（未配置重试=不重试）的失败本身已由触发点二（dispatch 三态）或
            // 触发点三（巡检 TIMEOUT）承载，不叠加 RETRY_EXHAUSTED——否则同 jobKey 每次失败
            // 双原因外呼，破坏 AC-4 的 5 分钟窗内同 jobKey+reason 恰 1 次去重口径。
            if (policy.maxAttempts() > 0) {
                alertNotifier.alert(definition.getJobKey(), SchedulerAlertNotifier.REASON_RETRY_EXHAUSTED,
                        failedExecution.getId(), attempt, failedExecution.getError());
            }
            return;
        }
        Instant now = clock.now();
        SchJobExecution retry = new SchJobExecution();
        retry.setDefinitionId(definition.getId());
        retry.setJobKey(definition.getJobKey());
        retry.setTriggerType(com.acme.platform.scheduler.api.ExecutionTriggerType.RETRY);
        retry.setFireTime(policy.nextRetryTime(now, attempt));
        retry.setState(com.acme.platform.scheduler.api.ExecutionState.PENDING);
        retry.setAttempt(attempt + 1);
        retry.setShardIndex(failedExecution.getShardIndex());
        retry.setError(failedExecution.getError());
        retry.setTraceId(failedExecution.getTraceId() != null
                ? failedExecution.getTraceId()
                : (Traces.current() != null ? Traces.current().traceId() : Traces.newTraceId()));
        executionRepository.save(retry);
        log.info("重试链续期：jobKey={}, attempt={}, fireTime={}, source={}",
                definition.getJobKey(), attempt + 1, retry.getFireTime(), failedExecution.getId());
    }
}
