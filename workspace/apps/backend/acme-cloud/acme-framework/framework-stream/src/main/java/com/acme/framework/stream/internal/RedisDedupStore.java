package com.acme.framework.stream.internal;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis 去重键实现（FR-014、Q-002）：Lua 保证认领/释放原子（M-12 不丢首投）。
 * 值 {@code P}=在途占位（{@code acme.stream.dedup.processing-ttl}），值 {@code D}=已完成（完成 TTL）。
 */
public final class RedisDedupStore implements DedupStore {

    /** 在途占位标记。 */
    private static final String PROCESSING = "P";

    /** 已完成标记。 */
    private static final String DONE = "D";

    /** 认领：GET 判态 + SET NX 写 P（原子）；0=重复、-1=在途、1=认领。 */
    private static final RedisScript<Long> TRY_BEGIN = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if v == 'D' then return 0 end
            if v == 'P' then return -1 end
            if redis.call('SET', KEYS[1], 'P', 'EX', ARGV[1], 'NX') then return 1 end
            return -1
            """, Long.class);

    /** 释放：仅当值仍为 P 时删除（勿误删并发 markDone 的 D）。 */
    private static final RedisScript<Long> RELEASE_IF_PROCESSING = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == 'P' then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisDedupStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Claim tryBegin(String key, Duration processingTtl) {
        Long result = redisTemplate.execute(TRY_BEGIN, List.of(key),
                String.valueOf(Math.max(processingTtl == null ? 600 : processingTtl.getSeconds(), 1)));
        if (result == null) {
            throw new IllegalStateException("去重认领脚本无返回");
        }
        return switch (result.intValue()) {
            case 1 -> Claim.CLAIMED;
            case 0 -> Claim.DUPLICATE;
            default -> Claim.IN_FLIGHT;
        };
    }

    @Override
    public void markDone(String key, Duration doneTtl) {
        redisTemplate.opsForValue().set(key, DONE,
                Duration.ofSeconds(Math.max(doneTtl == null ? 72 * 3600 : doneTtl.getSeconds(), 1)));
    }

    @Override
    public void markFailed(String key) {
        redisTemplate.execute(RELEASE_IF_PROCESSING, List.of(key));
    }
}
