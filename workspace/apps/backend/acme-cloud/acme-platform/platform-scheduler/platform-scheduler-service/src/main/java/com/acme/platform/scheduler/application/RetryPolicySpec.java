package com.acme.platform.scheduler.application;

import jakarta.validation.constraints.Positive;
import tools.jackson.databind.json.JsonMapper;

/**
 * 重试策略入参结构（契约确认③：retry_policy jsonb {"maxAttempts":n,"initialIntervalMs":ms}；
 * 结构化入参 → jsonb 文本持久化，管理面易用性优先）。可空（null=不重试）。
 *
 * @param maxAttempts        重试上限（缺省 0=不重试）
 * @param initialIntervalMs  重试首间隔毫秒（>0 时必填正数）
 */
public record RetryPolicySpec(Integer maxAttempts, @Positive Long initialIntervalMs) {

    /** jsonb 落库专用裸 mapper（数值直出；全局 Long→String 序列化仅面向 openapi wire 面）。 */
    private static final tools.jackson.databind.json.JsonMapper PLAIN_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** 序列化为 jsonb 文本（null=不重试 → 存 null；独立裸 mapper——数值直出，不经全局 Long→String 模块）。 */
    public String toJson(JsonMapper jsonMapper) {
        if (maxAttempts() == null || maxAttempts() <= 0) {
            return null;
        }
        long interval = initialIntervalMs() == null ? 5_000L : initialIntervalMs();
        return PLAIN_JSON.writeValueAsString(new RetryPolicySpec(maxAttempts(), interval));
    }

    /** jsonb 文本反序列化（null/空白 → 不重试）。 */
    public static RetryPolicySpec fromJson(String json, JsonMapper jsonMapper) {
        if (json == null || json.isBlank()) {
            return new RetryPolicySpec(0, null);
        }
        return jsonMapper.readValue(json, RetryPolicySpec.class);
    }
}
