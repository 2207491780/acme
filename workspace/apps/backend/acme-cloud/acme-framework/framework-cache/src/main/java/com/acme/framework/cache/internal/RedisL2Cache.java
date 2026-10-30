package com.acme.framework.cache.internal;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * L2 实现（架构设计 6.1）：Redis 字符串存储 + {@link JsonRedisSerializer}；
 * Redis 失败向上抛出，由 TwoLevelCache 按 {@code acme.cache.l2-failure} 策略处置（6.5）。
 */
public final class RedisL2Cache implements L2Operations {

    private final StringRedisTemplate redisTemplate;
    private final JsonRedisSerializer serializer;

    public RedisL2Cache(StringRedisTemplate redisTemplate, JsonRedisSerializer serializer) {
        this.redisTemplate = redisTemplate;
        this.serializer = serializer;
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        String raw = redisTemplate.opsForValue().get(key);
        return raw == null ? null : serializer.deserialize(raw, type);
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        String raw = serializer.serialize(value);
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(key, raw, Duration.ofSeconds(ttlSeconds));
        } else {
            redisTemplate.opsForValue().set(key, raw);
        }
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }
}
