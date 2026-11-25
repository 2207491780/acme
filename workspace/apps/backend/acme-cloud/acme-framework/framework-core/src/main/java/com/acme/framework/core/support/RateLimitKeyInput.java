package com.acme.framework.core.support;

import com.acme.common.context.Channel;

/**
 * 限流 key 构造输入（Modules.md 3.1.3 维度要素；p5-optional-modules D-001 自 framework-gateway 迁入）。
 *
 * <p>{@code tenantId} 为租户维度（REQ-20261125-001736 AC-6，为 per-tenant 限流铺路）：非空白时
 * {@link DefaultRateLimitKeyBuilder} 在服务前缀后插入租户段；空白/null 时键格式与既有输出逐字一致
 * （向后兼容）。FB 兜底键不含租户段。</p>
 */
public record RateLimitKeyInput(String servicePrefix, Channel channel, String account,
                                String deviceId, String userId, String api, String tenantId) {
}
