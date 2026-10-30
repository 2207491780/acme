package com.acme.framework.core.support;

/**
 * 限流 key 构造器（Modules.md 3.1.3：framework-gateway 与 framework-ratelimit 统一构造，业务代码不得自造限流键）。
 * 主键口径：B 端「服务前缀 + 端别 B + 账号 + 接口」、C 端「服务前缀 + 端别 C + 设备 + 用户 + 接口」；
 * 兜底键口径：「服务前缀 + 端别标识 FB + IP + 接口」。
 *
 * <p>p5-optional-modules D-001：自 framework-gateway 迁入（键格式不变，兼容既有产出）；
 * gateway 与 ratelimit 经 {@code @ConditionalOnMissingBean} 共用同一 bean，业务替换一次两端生效。</p>
 */
public interface RateLimitKeyBuilder {

    /** 构造限流 key（主键口径）。 */
    String build(RateLimitKeyInput input);

    /** 构造兜底限流 key（IP+接口）。 */
    String buildFallback(RateLimitFallbackInput input);
}
