package com.acme.framework.ratelimit.internal;

/**
 * 令牌桶判定面（internal）：实现可测替换（FR-016 语义在 RedisTokenBucket/Lua 原子判定）。
 */
public interface TokenBucket {

    /**
     * 尝试取一个许可。
     *
     * @param key                     计数键（已由共享构造器产出）
     * @param capacity                桶容量
     * @param refillPermitsPerSecond  每秒补充许可数
     * @return true=放行
     */
    boolean tryAcquire(String key, long capacity, double refillPermitsPerSecond);
}
