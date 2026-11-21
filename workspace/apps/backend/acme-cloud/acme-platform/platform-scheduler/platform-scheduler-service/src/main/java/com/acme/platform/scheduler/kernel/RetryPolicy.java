package com.acme.platform.scheduler.kernel;

import com.acme.common.core.BizException;
import com.acme.platform.scheduler.SchedulerError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

/**
 * 重试策略（契约确认③：sch_job_definition.retry_policy jsonb {"maxAttempts":n,"initialIntervalMs":ms}）。
 * 解析容错：空/缺省=不重试（maxAttempts=0）；字段非法抛 SCHEDULER_RETRY_POLICY_INVALID。
 * 退避策略（EXPONENTIAL + jitter，设计规格 §2 列名提示）归 JOB-4 细化——本 REQ 按
 * initialIntervalMs 恒定间隔生成 RETRY execution（确认①：重试细节归 JOB-4，仅保留链路语义）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryPolicy(@JsonProperty("maxAttempts") int maxAttempts,
                          @JsonProperty("initialIntervalMs") long initialIntervalMs) {

    /** JSON 文本解析（null/空白 → 不重试缺省）。 */
    public static RetryPolicy parse(String json, JsonMapper jsonMapper) {
        if (json == null || json.isBlank()) {
            return none();
        }
        RetryPolicy policy = jsonMapper.readValue(json, RetryPolicy.class);
        if (policy.maxAttempts() < 0) {
            throw new BizException(SchedulerError.SCHEDULER_RETRY_POLICY_INVALID,
                    "maxAttempts 不允许为负数: " + policy.maxAttempts());
        }
        if (policy.maxAttempts() > 0 && policy.initialIntervalMs() <= 0) {
            throw new BizException(SchedulerError.SCHEDULER_RETRY_POLICY_INVALID,
                    "maxAttempts>0 时 initialIntervalMs 必须为正数毫秒: " + policy.initialIntervalMs());
        }
        return policy;
    }

    /** 不重试缺省策略。 */
    public static RetryPolicy none() {
        return new RetryPolicy(0, 0L);
    }

    /** 校验策略合法性（创建/更新入口）。 */
    public static void validate(String json, JsonMapper jsonMapper) {
        parse(json, jsonMapper);
    }

    /**
     * 是否可重试（当前轮次 attempt 的下一次仍在配额内）。
     */
    public boolean canRetry(int attempt) {
        return attempt + 1 <= maxAttempts;
    }

    /** 重试触发时刻（now + 恒定首间隔；指数退避归 JOB-4）。 */
    public java.time.Instant nextRetryTime(java.time.Instant now) {
        return now.plusMillis(initialIntervalMs);
    }
}
