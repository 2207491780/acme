package com.acme.framework.tenant.support;

import com.acme.common.core.BizException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨租户审计结构化日志（FR-004/AC-004，Q-005：不落库）：traceId/操作人/涉及租户/访问点；
 * 旁路期间实际触及全租户集合，tenant 记 {@code *} 并带 bypass=ROOT 标记（12.4.2 审计完整性）；
 * 审计产生失败 fail-closed（阻断访问并 ERROR 告警，12.4.2 纳入审计为强制前置）。
 */
public class CrossTenantAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("acme.tenant.audit");

    /**
     * 记录一次跨租户访问。
     *
     * @throws BizException 审计失败（AUDIT_FAILED，fail-closed）
     */
    public void record(String accessPoint) {
        try {
            TraceContext trace = TraceContext.get();
            String traceId = trace == null || trace.traceId() == null ? "-" : trace.traceId();
            if (TenantBypassScope.isActive()) {
                // 旁路触及全租户集合：范围记 *（12.4.2）
                log.info("cross-tenant-audit traceId={} operator={} tenant=* bypass=ROOT accessPoint={}",
                        traceId, operator(), accessPoint);
                return;
            }
            TenantContext tenant = TenantContext.get();
            String tenantId = tenant == null || tenant.tenantId() == null ? "-" : tenant.tenantId();
            log.info("cross-tenant-audit traceId={} operator={} tenant={} bypass=false accessPoint={}",
                    traceId, operator(), tenantId, accessPoint);
        } catch (RuntimeException ex) {
            log.error("跨租户审计失败，阻断该次访问（FR-004 fail-closed）: {}", ex.getMessage());
            throw new BizException(TenantError.AUDIT_FAILED, "跨租户审计失败", ex);
        }
    }

    /** 操作人取值（缺失回退 system）。 */
    static String operator() {
        UserContext user = UserContext.get();
        return user == null || user.userId() == null ? "system" : user.userId();
    }
}
