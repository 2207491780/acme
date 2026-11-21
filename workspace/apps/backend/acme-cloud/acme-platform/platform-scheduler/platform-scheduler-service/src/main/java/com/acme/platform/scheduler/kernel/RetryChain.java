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
 * RETRY execution（attempt+1）；maxAttempts 耗尽→FAILED 终态，告警归 JOB-4）。
 * 重试调度口径：RETRY execution 以 PENDING 落库、fire_time=now+initialIntervalMs，
 * 由内核 tick 的 PENDING 恢复扫描到期下发（重试链与首发统一恢复口径，进程重启不丢重试）。
 */
@Component
public class RetryChain {

    private static final Logger log = LoggerFactory.getLogger(RetryChain.class);

    private final JobExecutionRepository executionRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public RetryChain(JobExecutionRepository executionRepository, JsonMapper jsonMapper, Clock schedulerClock) {
        this.executionRepository = executionRepository;
        this.jsonMapper = jsonMapper;
        this.clock = schedulerClock;
    }

    /**
     * 依策略续链（失败源 execution 已终态 FAILED/TIMEOUT）：
     * 配额未尽→插入 RETRY execution（attempt+1，PENDING，fire_time=now+间隔）；耗尽→仅告警日志（JOB-4 接管）。
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
            log.warn("重试链耗尽（终态 FAILED，告警归 JOB-4）：jobKey={}, attempt={}, maxAttempts={}, lastError={}",
                    definition.getJobKey(), attempt, policy.maxAttempts(), failedExecution.getError());
            return;
        }
        Instant now = clock.now();
        SchJobExecution retry = new SchJobExecution();
        retry.setDefinitionId(definition.getId());
        retry.setJobKey(definition.getJobKey());
        retry.setTriggerType(com.acme.platform.scheduler.api.ExecutionTriggerType.RETRY);
        retry.setFireTime(policy.nextRetryTime(now));
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
