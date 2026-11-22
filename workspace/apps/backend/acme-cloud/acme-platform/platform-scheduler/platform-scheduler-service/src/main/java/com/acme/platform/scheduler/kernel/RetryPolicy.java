package com.acme.platform.scheduler.kernel;

import com.acme.common.core.BizException;
import com.acme.platform.scheduler.SchedulerError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试策略（契约确认③ jsonb；REQ-20261122-150756 确认②向后兼容扩展）：
 * {@code {"maxAttempts":n,"initialIntervalMs":ms,"backoff":"FIXED"|"EXPONENTIAL","multiplier":2,"maxIntervalMs":ms}}。
 * <ul>
 *   <li>旧两键形状语义不变：backoff 缺省=FIXED=initialIntervalMs 恒定间隔（无 jitter，
 *       AC-1「现行为不变」）；</li>
 *   <li>EXPONENTIAL：间隔=initialIntervalMs × multiplier^attempt（multiplier 缺省 2），
 *       叠加 ±10% 固定比例 jitter（不新增配置键），再按 maxIntervalMs 封顶（缺省不封顶）；</li>
 *   <li>解析容错：空/缺省=不重试（maxAttempts=0）；字段非法抛 SCHEDULER_RETRY_POLICY_INVALID。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryPolicy(@JsonProperty("maxAttempts") int maxAttempts,
                          @JsonProperty("initialIntervalMs") long initialIntervalMs,
                          @JsonProperty("backoff") Backoff backoff,
                          @JsonProperty("multiplier") Double multiplier,
                          @JsonProperty("maxIntervalMs") Long maxIntervalMs) {

    /** 退避策略（确认②：FIXED 缺省=现行为；EXPONENTIAL 按 multiplier 递增）。 */
    public enum Backoff {
        FIXED, EXPONENTIAL
    }

    /** jitter 固定比例 ±10%（确认②：不新增配置键）。 */
    static final double JITTER_RATIO = 0.10d;

    /** multiplier 缺省值（确认②：EXPONENTIAL 缺省 2）。 */
    static final double DEFAULT_MULTIPLIER = 2.0d;

    /** 两键兼容构造（旧形状=FIXED 无封顶）。 */
    public RetryPolicy(int maxAttempts, long initialIntervalMs) {
        this(maxAttempts, initialIntervalMs, Backoff.FIXED, null, null);
    }

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
        if (policy.multiplier() != null && policy.multiplier() <= 1d) {
            throw new BizException(SchedulerError.SCHEDULER_RETRY_POLICY_INVALID,
                    "multiplier 必须大于 1: " + policy.multiplier());
        }
        if (policy.maxIntervalMs() != null && policy.maxIntervalMs() <= 0) {
            throw new BizException(SchedulerError.SCHEDULER_RETRY_POLICY_INVALID,
                    "maxIntervalMs 必须为正数毫秒: " + policy.maxIntervalMs());
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

    /**
     * 重试触发时刻（now + 退避间隔）。
     *
     * @param attempt 失败源 execution 的当前轮次（0 起；EXPONENTIAL 以此为指数基）
     */
    public Instant nextRetryTime(Instant now, int attempt) {
        return now.plusMillis(computeIntervalMs(attempt));
    }

    /**
     * 退避间隔计算（包内可见供测试断言边界）：
     * FIXED=initialIntervalMs 恒定；EXPONENTIAL=initialIntervalMs × multiplier^attempt，
     * 叠加 ±10% jitter 后按 maxIntervalMs 封顶。
     */
    long computeIntervalMs(int attempt) {
        if (backoff() != Backoff.EXPONENTIAL) {
            return initialIntervalMs();
        }
        double mult = multiplier() == null ? DEFAULT_MULTIPLIER : multiplier();
        long raw = Math.round(initialIntervalMs() * Math.pow(mult, attempt));
        double jitter = ThreadLocalRandom.current().nextDouble(-JITTER_RATIO, JITTER_RATIO);
        long jittered = Math.round(raw * (1d + jitter));
        if (maxIntervalMs() != null && maxIntervalMs() > 0) {
            return Math.min(jittered, maxIntervalMs());
        }
        return jittered;
    }
}
