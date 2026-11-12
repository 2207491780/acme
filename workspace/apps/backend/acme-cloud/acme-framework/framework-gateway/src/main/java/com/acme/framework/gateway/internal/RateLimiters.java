package com.acme.framework.gateway.internal;

import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 限流执行器（FR-022、Q-D07：Resilience4j 本地内存计数）：按 key 独立计数，配额按端（11.4）；
 * 兜底键独立配额（WQ-005）；计数表 LRU 有界（maxTrackedKeys，防高基数路径内存无限增长）；
 * 分布式计数留 P4/P5 演进（3.2 framework-ratelimit 边界）。
 */
public class RateLimiters {

    private final Map<String, RateLimiter> limiters;
    private final int bQuotaPerMinute;
    private final int cQuotaPerMinute;
    private final int fallbackQuotaPerMinute;

    public RateLimiters(GatewayProperties.Ratelimit config) {
        this.bQuotaPerMinute = config.getB().getQuotaPerMinute();
        this.cQuotaPerMinute = config.getC().getQuotaPerMinute();
        this.fallbackQuotaPerMinute = config.getFallback().getQuotaPerMinute();
        int maxTrackedKeys = config.getMaxTrackedKeys();
        this.limiters = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RateLimiter> eldest) {
                return size() > maxTrackedKeys;
            }
        });
    }

    /** 是否放行（FR-022，主键口径）。 */
    public boolean tryAcquire(String key, Channel channel) {
        return tryAcquire(key, channel == Channel.C ? cQuotaPerMinute : bQuotaPerMinute);
    }

    /** 是否放行（WQ-005，兜底键口径）。 */
    public boolean tryAcquireFallback(String key) {
        return tryAcquire(key, fallbackQuotaPerMinute);
    }

    private boolean tryAcquire(String key, int quotaPerMinute) {
        RateLimiter limiter = limiters.computeIfAbsent(key + "@" + quotaPerMinute, k -> create(quotaPerMinute));
        return limiter.acquirePermission();
    }

    private static RateLimiter create(int quotaPerMinute) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(quotaPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of("acme-gateway", config);
    }
}
