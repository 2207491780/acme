package com.acme.framework.core.support;

/**
 * 限流判定异常兜底策略（与网关口径同源，默认 FAIL_OPEN 可用性优先；
 * p5-optional-modules D-001 自 framework-gateway 迁入）。
 */
public enum RateLimitOnError {

    /** 判定异常放行（默认，可用性优先）。 */
    FAIL_OPEN,

    /** 判定异常拒绝。 */
    FAIL_CLOSE
}
