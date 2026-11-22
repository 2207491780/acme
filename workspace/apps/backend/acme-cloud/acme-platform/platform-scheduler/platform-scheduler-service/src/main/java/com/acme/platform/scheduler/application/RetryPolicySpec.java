package com.acme.platform.scheduler.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 重试策略入参结构（契约确认③ + REQ-20261122-150756 确认②向后兼容扩展）：
 * 结构化入参 → jsonb 文本持久化，管理面易用性优先。可空（null=不重试）。
 * <ul>
 *   <li>旧两键形状语义不变：backoff/multiplier/maxIntervalMs 均可空，缺省序列化省略
 *       （落库文本保持 {"maxAttempts":n,"initialIntervalMs":ms} 两键原样）；</li>
 *   <li>扩展键：backoff（"FIXED"|"EXPONENTIAL"，缺省 FIXED=现行为）、multiplier（EXPONENTIAL
 *       缺省 2）、maxIntervalMs（封顶，缺省不封顶）；合法性（multiplier&gt;1 等）由
 *       {@link com.acme.platform.scheduler.kernel.RetryPolicy#validate} 在用例层统一校验。</li>
 * </ul>
 *
 * @param maxAttempts        重试上限（缺省 0=不重试）
 * @param initialIntervalMs  重试首间隔毫秒（&gt;0 时必填正数）
 * @param backoff            退避策略（可空=FIXED；REQ-20261122-150756 确认②）
 * @param multiplier         EXPONENTIAL 递增系数（可空=2）
 * @param maxIntervalMs      退避间隔封顶毫秒（可空=不封顶）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryPolicySpec(Integer maxAttempts, @Positive Long initialIntervalMs,
                              String backoff, Double multiplier, Long maxIntervalMs) {

    /** jsonb 落库专用裸 mapper（数值直出；全局 Long→String 序列化仅面向 openapi wire 面）。 */
    private static final tools.jackson.databind.json.JsonMapper PLAIN_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /**
     * 序列化为 jsonb 文本（null=不重试 → 存 null；扩展键仅在有值时输出——
     * 旧两键形状落库文本逐字节不变，backoff 缺省语义由 RetryPolicy 承载）。
     */
    public String toJson(JsonMapper jsonMapper) {
        if (maxAttempts() == null || maxAttempts() <= 0) {
            return null;
        }
        ObjectNode node = PLAIN_JSON.createObjectNode();
        node.put("maxAttempts", maxAttempts());
        node.put("initialIntervalMs", initialIntervalMs() == null ? 5_000L : initialIntervalMs());
        if (backoff() != null && !backoff().isBlank()) {
            node.put("backoff", backoff().trim());
        }
        if (multiplier() != null) {
            node.put("multiplier", multiplier());
        }
        if (maxIntervalMs() != null) {
            node.put("maxIntervalMs", maxIntervalMs());
        }
        return PLAIN_JSON.writeValueAsString(node);
    }

    /** jsonb 文本反序列化（null/空白 → 不重试；两键/五键形状均兼容）。 */
    public static RetryPolicySpec fromJson(String json, JsonMapper jsonMapper) {
        if (json == null || json.isBlank()) {
            return new RetryPolicySpec(0, null, null, null, null);
        }
        return jsonMapper.readValue(json, RetryPolicySpec.class);
    }
}
