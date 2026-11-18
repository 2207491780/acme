package com.acme.framework.cache.internal;

import com.acme.framework.cache.interceptor.PenetrationGuard;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 防击穿入口锁 Redis 实现（架构设计 6.4：SET NX 短租约；自持 Redis，Modules.md 4.3 不依赖 framework-lock）。
 * 职责边界：本锁=缓存击穿单飞的轻量租约（无重入/看门狗/失败策略），
 * 与 framework-lock 的可重入业务互斥锁（{@code DistributedLocks}）不同层，不合并下沉。
 * 释放按持有者 token 比对（Lua），租约过期后不误删他实例锁。
 */
public final class RedisEntryLock implements PenetrationGuard.EntryLock {

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisEntryLock(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public String tryLock(String key, long leaseMillis) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(keyPrefix + key, token, Duration.ofMillis(Math.max(leaseMillis, 1)));
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    @Override
    public void release(String key, String token) {
        if (token == null) {
            return;
        }
        redisTemplate.execute(RELEASE, List.of(keyPrefix + key), token);
    }
}
