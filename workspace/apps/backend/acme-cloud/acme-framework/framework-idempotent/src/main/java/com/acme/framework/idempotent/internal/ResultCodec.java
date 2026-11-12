package com.acme.framework.idempotent.internal;

/**
 * 幂等结果缓存编解码面（internal）：结果 JSON 与方法返回值互转（FR-001 结果缓存）。
 */
public interface ResultCodec {

    /** 结果 → JSON。 */
    String encode(Object value);

    /** JSON → 结果。 */
    <T> T decode(String json, Class<T> type);
}
