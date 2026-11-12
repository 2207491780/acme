package com.acme.framework.cache.internal;

import com.acme.common.core.BizException;
import com.acme.common.serialization.Json;
import com.acme.framework.cache.support.CacheError;

/**
 * 值序列化承载（架构设计 6.1）：common-serialization {@link Json}（Jackson 3，无 Jackson 2 触碰）；
 * 编解码失败显式失败（CACHE_UNAVAILABLE），不返回脏值（FR-005 异常分支）。
 */
public final class JsonRedisSerializer {

    private final Json json;

    public JsonRedisSerializer(Json json) {
        this.json = json;
    }

    public String serialize(Object value) {
        try {
            return json.toJson(value);
        } catch (RuntimeException ex) {
            throw new BizException(CacheError.CACHE_UNAVAILABLE, "缓存值序列化失败", ex);
        }
    }

    public <T> T deserialize(String raw, Class<T> type) {
        try {
            return json.fromJson(raw, type);
        } catch (RuntimeException ex) {
            throw new BizException(CacheError.CACHE_UNAVAILABLE, "缓存值反序列化失败", ex);
        }
    }
}
