package com.acme.platform.system.api;

import java.time.Instant;

/**
 * 租户详情内部视图（REQ-20261117-025200 by-code 契约）：platform-auth 登录过期校验与
 * 超管跨租户切换目标校验的数据源，以服务身份主体调用（Q-D08，同 TenantClient 口径）。
 * REQ-20261122-150802 增补 graceUntil（登录闸门派生输入：宽限期内仍可登录，确认②；
 * 兼容构造（REQ-150802 之前调用面）graceUntil 缺省 null=未配置宽限）。
 *
 * @param tenantCode 租户代码（全局唯一键）
 * @param tenantName 租户名称
 * @param status     启停状态（{@link EnableStatus} 枚举名；非 ENABLED 不可登录 / 不可切换）
 * @param expiresAt  租户有效期（可空=长期；非空且已过期且无有效宽限按不可登录 / 不可切换处理，确认③④）
 * @param graceUntil 宽限期止点（可空=未配置宽限；expires 过且 grace 未过=宽限期仍可登录，确认②）
 */
public record TenantInternalView(String tenantCode, String tenantName, String status, Instant expiresAt,
                                 Instant graceUntil) {

    /** 兼容构造（REQ-20261122-150802 之前调用面）：graceUntil 缺省 null=未配置宽限。 */
    public TenantInternalView(String tenantCode, String tenantName, String status, Instant expiresAt) {
        this(tenantCode, tenantName, status, expiresAt, null);
    }
}
