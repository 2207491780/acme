package com.acme.framework.tenant.support;

import java.time.Instant;

/**
 * 跨租户审计事件（FR-004；REQ-20261125-001736 AC-2）：经 {@link CrossTenantAuditSink} SPI 投递的
 * 自包含载荷——字段在 record 调用线程取值并归一，不依赖 ThreadLocal（异步消费线程可独立判读）。
 *
 * <p>取值口径与 {@code acme.tenant.audit} 同步日志逐字段对齐：{@code tenantId} 旁路期间记
 * {@code *}（触及全租户集合，12.4.2），直读事件记实际租户（无绑定记 {@code -}）；{@code bypass}
 * 取值 {@link #BYPASS_ROOT}=旁路开启、{@link #BYPASS_FALSE}=单租户直读；{@code operator} 缺失回退
 * {@code system}。</p>
 */
public record CrossTenantAuditEvent(String operator, String tenantId, String accessPoint,
                                    String traceId, String bypass, Instant occurredAt) {

    /** 旁路开启标记（触及全租户集合，12.4.2；与同步日志 bypass=ROOT 一致）。 */
    public static final String BYPASS_ROOT = "ROOT";

    /** 单租户直读标记（与同步日志 bypass=false 一致）。 */
    public static final String BYPASS_FALSE = "false";

    public CrossTenantAuditEvent {
        operator = operator == null || operator.isBlank() ? "system" : operator;
        tenantId = tenantId == null || tenantId.isBlank() ? "-" : tenantId;
        accessPoint = accessPoint == null || accessPoint.isBlank() ? "-" : accessPoint;
        traceId = traceId == null || traceId.isBlank() ? "-" : traceId;
        bypass = bypass == null || bypass.isBlank() ? BYPASS_FALSE : bypass;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
