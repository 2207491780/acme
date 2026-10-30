package com.acme.framework.core.support;

import com.acme.common.context.Channel;

/**
 * 限流 key 构造输入（Modules.md 3.1.3 维度要素；p5-optional-modules D-001 自 framework-gateway 迁入）。
 */
public record RateLimitKeyInput(String servicePrefix, Channel channel, String account,
                                String deviceId, String userId, String api) {
}
