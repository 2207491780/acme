package com.acme.framework.tenant.support;

import com.acme.common.core.BizException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * 跨租户审计结构化日志（FR-004/AC-004，Q-005 修订：日志兜底 + sink 尽力落库，REQ-20261125-001736
 * AC-2/AC-3）：traceId/操作人/涉及租户/访问点；旁路期间实际触及全租户集合，tenant 记 {@code *}
 * 并带 bypass=ROOT 标记（12.4.2 审计完整性）；审计产生失败 fail-closed（阻断访问并 ERROR 告警，
 * 12.4.2 纳入审计为强制前置）。
 *
 * <p>SPI 接线（AC-2）：同步日志照旧输出后，把自包含事件投递给可选 {@link CrossTenantAuditSink}
 * （旁路 ROOT 与直读事件都投递）；未装配 sink 仅日志不报错（框架可独立运行）；sink 投递失败
 * 仅 WARN 不阻断（异步尽力落库口径，AC-3——同步日志是兜底事实源，落库失败不产生 fail-closed）。</p>
 */
public class CrossTenantAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("acme.tenant.audit");

    /** 运行态告警面（sink 投递失败 WARN、审计产生失败 ERROR）：与审计事实流 acme.tenant.audit 分离，
     *  不向审计 logger 混入运维级事件（审计流每访问恰一条结构化事实，AC-2/AC-3）。 */
    private static final Logger opsLog = LoggerFactory.getLogger(CrossTenantAuditLogger.class);

    /** 可选投递面（ObjectProvider 装配；无 sink 时框架可独立运行）。 */
    private final CrossTenantAuditSink sink;

    /** 无 sink 装配（既有形态；仅同步日志）。 */
    public CrossTenantAuditLogger() {
        this(null);
    }

    /** 可选 sink 装配（REQ-20261125-001736 AC-2）。 */
    public CrossTenantAuditLogger(CrossTenantAuditSink sink) {
        this.sink = sink;
    }

    /**
     * 记录一次跨租户访问。
     *
     * @throws BizException 审计失败（AUDIT_FAILED，fail-closed）
     */
    public void record(String accessPoint) {
        try {
            TraceContext trace = TraceContext.get();
            String traceId = trace == null || trace.traceId() == null ? "-" : trace.traceId();
            Instant occurredAt = Instant.now();
            String operator = operator();
            if (TenantBypassScope.isActive()) {
                // 旁路触及全租户集合：范围记 *（12.4.2）
                log.info("cross-tenant-audit traceId={} operator={} tenant=* bypass=ROOT accessPoint={}",
                        traceId, operator, accessPoint);
                deliver(new CrossTenantAuditEvent(operator, "*", accessPoint, traceId,
                        CrossTenantAuditEvent.BYPASS_ROOT, occurredAt));
                return;
            }
            TenantContext tenant = TenantContext.get();
            String tenantId = tenant == null || tenant.tenantId() == null ? "-" : tenant.tenantId();
            log.info("cross-tenant-audit traceId={} operator={} tenant={} bypass=false accessPoint={}",
                    traceId, operator, tenantId, accessPoint);
            deliver(new CrossTenantAuditEvent(operator, tenantId, accessPoint, traceId,
                    CrossTenantAuditEvent.BYPASS_FALSE, occurredAt));
        } catch (RuntimeException ex) {
            opsLog.error("跨租户审计失败，阻断该次访问（FR-004 fail-closed）: {}", ex.getMessage());
            throw new BizException(TenantError.AUDIT_FAILED, "跨租户审计失败", ex);
        }
    }

    /** 操作人取值（缺失回退 system）。 */
    static String operator() {
        UserContext user = UserContext.get();
        return user == null || user.userId() == null ? "system" : user.userId();
    }

    /** sink 投递单段（尽力口径：无 sink 空操作；实现抛出仅 WARN 不阻断——AC-3 主流程零影响）。 */
    private void deliver(CrossTenantAuditEvent event) {
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event);
        } catch (RuntimeException ex) {
            opsLog.warn("跨租户审计 sink 投递失败，忽略（同步日志兜底已输出）: {}", ex.getMessage());
        }
    }
}
