package com.acme.framework.cache.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * L1 实现（架构设计 6.1）：Caffeine 本地缓存（max-size + TTL）；L1 命中零 L2 访问（NFR-003 结构保证）。
 */
public final class CaffeineL1Cache {

    private final Cache<String, Object> cache;

    public CaffeineL1Cache(long maxSize, long ttlSeconds) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(Math.max(ttlSeconds, 1)))
                .build();
    }

    public Object getIfPresent(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, Object value) {
        if (value != null) {
            cache.put(key, value);
        }
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
