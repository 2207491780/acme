package com.acme.framework.core.support;

/**
 * 兜底限流 key 构造输入（公开端点/未鉴权流量，key=IP+接口；p5-optional-modules D-001 自 framework-gateway 迁入）。
 */
public record RateLimitFallbackInput(String servicePrefix, String clientIp, String api) {
}
