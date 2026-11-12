package com.acme.framework.idempotent.internal;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis 幂等记录实现（FR-001/003、Q-006：单 hash 键 {@code {svc}:idem:http:{token}}，
 * 字段 {@code s}=P/D、{@code r}=结果 JSON）；认领/完成/释放均 Lua 原子（FR-003）。
 */
public class RedisIdempotentRecordStore implements IdempotentRecordStore {

    private static final String PROCESSING = "P";
    private static final String DONE = "D";

    /** 认领：s=D→0（重复）、s=P→-1（在途）、否则写 P + 短 TTL→1（认领）。 */
    private static final RedisScript<Long> TRY_BEGIN = new DefaultRedisScript<>("""
            local s = redis.call('HGET', KEYS[1], 's')
            if s == 'D' then return 0 end
            if s == 'P' then return -1 end
            redis.call('HSET', KEYS[1], 's', 'P')
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    /** 完成：单键原子写 s=D + r=结果 + 结果 TTL（FR-003）。 */
    private static final RedisScript<Long> MARK_DONE = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 's', 'D', 'r', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /** 释放：仅删 P 占位（勿误删并发 markDone 的 D）。 */
    private static final RedisScript<Long> RELEASE_IF_PROCESSING = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 's') == 'P' then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisIdempotentRecordStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Claim tryBegin(String key, Duration processingTtl) {
        Long result = redisTemplate.execute(TRY_BEGIN, List.of(key),
                String.valueOf(Math.max(processingTtl == null ? 60 : processingTtl.getSeconds(), 1)));
        if (result == null) {
            throw new IllegalStateException("幂等认领脚本无返回");
        }
        return switch (result.intValue()) {
            case 1 -> Claim.CLAIMED;
            case 0 -> Claim.DUPLICATE;
            default -> Claim.IN_FLIGHT;
        };
    }

    @Override
    public void markDone(String key, String resultJson, Duration resultTtl) {
        redisTemplate.execute(MARK_DONE, List.of(key),
                resultJson == null ? "" : resultJson,
                String.valueOf(Math.max(resultTtl == null ? 24 * 3600 : resultTtl.getSeconds(), 1)));
    }

    @Override
    public void markFailed(String key) {
        redisTemplate.execute(RELEASE_IF_PROCESSING, List.of(key));
    }

    @Override
    public String findResult(String key) {
        String result = redisTemplate.<String, String>boundHashOps(key).get("r");
        return result == null || result.isEmpty() ? null : result;
    }
}
