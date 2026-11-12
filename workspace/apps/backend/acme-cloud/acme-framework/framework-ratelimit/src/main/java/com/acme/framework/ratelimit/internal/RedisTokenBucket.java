package com.acme.framework.ratelimit.internal;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redis Lua 令牌桶（FR-016）：跨实例共享计数、Lua 原子判定；
 * 判定等待受 {@code acme.ratelimit.redis-timeout} 有界约束（NFR-003 无界阻塞禁止），超时按判定异常处置。
 */
public class RedisTokenBucket implements TokenBucket {

    /** 令牌桶脚本：补速 refill、容量 capacity、单次取 1；写回剩余令牌与时间戳并续 TTL。 */
    private static final RedisScript<Long> TRY_ACQUIRE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttlMs = tonumber(ARGV[4])
            local data = redis.call('HMGET', key, 't', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            local delta = math.max(0, now - ts) / 1000.0
            tokens = math.min(capacity, tokens + delta * refill)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', key, 't', tokens, 'ts', now)
            redis.call('PEXPIRE', key, ttlMs)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration redisTimeout;

    public RedisTokenBucket(StringRedisTemplate redisTemplate, Duration redisTimeout) {
        this.redisTemplate = redisTemplate;
        this.redisTimeout = redisTimeout == null ? Duration.ofMillis(100) : redisTimeout;
    }

    @Override
    public boolean tryAcquire(String key, long capacity, double refillPermitsPerSecond) {
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(redisTimeout.toMillis() * 10, 1000L);
        CompletableFuture<Long> future = CompletableFuture.supplyAsync(() ->
                redisTemplate.execute(TRY_ACQUIRE, List.of(key),
                        String.valueOf(capacity), String.valueOf(refillPermitsPerSecond),
                        String.valueOf(now), String.valueOf(ttlMs)));
        try {
            Long allowed = future.get(redisTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return allowed != null && allowed == 1L;
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IllegalStateException("限流判定超出 Redis 等待预算", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("限流判定被中断", ex);
        } catch (CompletionException ex) {
            throw new IllegalStateException("限流判定失败", ex.getCause());
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("限流判定失败", ex.getCause());
        }
    }
}
