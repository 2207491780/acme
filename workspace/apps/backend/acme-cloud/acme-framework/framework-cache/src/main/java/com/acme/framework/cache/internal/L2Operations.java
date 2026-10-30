package com.acme.framework.cache.internal;

/**
 * L2 操作面（internal）：隔离 Redis 实现，供 TwoLevelCache 读写删与防击穿双检复用（架构设计 6.1/6.4）。
 */
public interface L2Operations {

    /** 读取反序列化值；未命中返回 null；失败按调用方策略处置。 */
    <T> T get(String key, Class<T> type);

    /** 写入（ttlSeconds=0 不设过期）。 */
    void put(String key, Object value, long ttlSeconds);

    /** 删除。 */
    void delete(String key);
}
