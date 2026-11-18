package com.acme.framework.gateway.internal;

import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关侧限流计数测试（FR-022、Q-D07）；key 构造测试随 D-001 迁 framework-core（RateLimitKeyBuilderTest）。
 */
class RateLimitSupportTest {

    @Test
    void limiterGrantsUpToQuota() {
        GatewayProperties.Ratelimit config = new GatewayProperties.Ratelimit();
        config.getB().setQuotaPerMinute(2);
        RateLimiters limiters = new RateLimiters(config);
        String key = "k1";
        assertThat(limiters.tryAcquire(key, Channel.B)).isTrue();
        assertThat(limiters.tryAcquire(key, Channel.B)).isTrue();
        assertThat(limiters.tryAcquire(key, Channel.B)).isFalse();
    }

    @Test
    void fallbackLimiterGrantsUpToItsOwnQuota() {
        GatewayProperties.Ratelimit config = new GatewayProperties.Ratelimit();
        config.getFallback().setQuotaPerMinute(2);
        RateLimiters limiters = new RateLimiters(config);
        assertThat(limiters.tryAcquireFallback("fb1")).isTrue();
        assertThat(limiters.tryAcquireFallback("fb1")).isTrue();
        assertThat(limiters.tryAcquireFallback("fb1")).isFalse();
        // 兜底计数与主键计数并行、互不占用（WQ-005）
        assertThat(limiters.tryAcquire("fb1", Channel.B)).isTrue();
    }
}
