package com.acme.framework.ratelimit.config;

import com.acme.framework.core.support.RateLimitOnError;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 服务侧限流配置（FR-016、8.1.3 全字段默认值；架构设计 15 章）。
 */
@ConfigurationProperties(prefix = "acme.ratelimit")
public class RatelimitProperties {

    /** 模块开关（3.5 可关闭性）。 */
    private boolean enabled = true;

    /** 默认桶容量（注解未声明时）。 */
    private long defaultCapacity = 100;

    /** 默认每秒补充许可数（注解未声明时）。 */
    private double defaultRefillPermitsPerSecond = 10;

    /** 判定异常兜底（默认 FAIL_OPEN，与网关口径同源，取舍 #15）。 */
    private RateLimitOnError onError = RateLimitOnError.FAIL_OPEN;

    /** 限流判定的 Redis 等待预算（NFR-003 无界阻塞禁止）。 */
    private Duration redisTimeout = Duration.ofMillis(100);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDefaultCapacity() {
        return defaultCapacity;
    }

    public void setDefaultCapacity(long defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public double getDefaultRefillPermitsPerSecond() {
        return defaultRefillPermitsPerSecond;
    }

    public void setDefaultRefillPermitsPerSecond(double defaultRefillPermitsPerSecond) {
        this.defaultRefillPermitsPerSecond = defaultRefillPermitsPerSecond;
    }

    public RateLimitOnError getOnError() {
        return onError;
    }

    public void setOnError(RateLimitOnError onError) {
        this.onError = onError;
    }

    public Duration getRedisTimeout() {
        return redisTimeout;
    }

    public void setRedisTimeout(Duration redisTimeout) {
        this.redisTimeout = redisTimeout;
    }
}
