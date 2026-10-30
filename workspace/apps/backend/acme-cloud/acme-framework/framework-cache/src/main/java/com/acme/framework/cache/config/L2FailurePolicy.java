package com.acme.framework.cache.config;

/**
 * L2（Redis）不可用处置策略（FR-005 异常分支授权设计、架构设计 6.5）：
 * {@link #DEGRADE} 降级直通回源（可用性优先，取舍 #13）；{@link #FAIL} 显式失败。
 */
public enum L2FailurePolicy {

    /** 降级直通回源 + WARN。 */
    DEGRADE,

    /** 显式失败（CACHE_UNAVAILABLE）。 */
    FAIL
}
