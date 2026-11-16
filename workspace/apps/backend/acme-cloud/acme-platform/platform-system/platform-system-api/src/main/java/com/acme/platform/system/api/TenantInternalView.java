package com.acme.platform.system.api;

import java.time.Instant;

/**
 * 租户详情内部视图（REQ-20261117-025200 by-code 契约）：platform-auth 登录过期校验与
 * 超管跨租户切换目标校验的数据源，以服务身份主体调用（Q-D08，同 TenantClient 口径）。
 *
 * @param tenantCode 租户代码（全局唯一键）
 * @param tenantName 租户名称
 * @param status     启停状态（{@link EnableStatus} 枚举名；非 ENABLED 不可登录 / 不可切换）
 * @param expiresAt  租户有效期（可空=长期；非空且已过期按不可登录 / 不可切换处理，确认记录③④）
 */
public record TenantInternalView(String tenantCode, String tenantName, String status, Instant expiresAt) {
}
