package com.acme.framework.tenant.interceptor;

import com.acme.framework.tenant.config.TenantProperties;
import com.acme.framework.tenant.support.CrossTenantAuditLogger;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.framework.tenant.support.TenantBypassScope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * {@link IgnoreTenant} AOP（FR-003/004、AC-003/004）：开旁路 → 执行 → 审计（成功或 fail-closed）→ 关旁路；
 * 注解滥用靠评审约束（BR-017，本模块不写业务规则）。
 */
@Aspect
public class IgnoreTenantAspect {

    private final CrossTenantAuditLogger auditLogger;
    private final TenantProperties properties;

    public IgnoreTenantAspect(CrossTenantAuditLogger auditLogger, TenantProperties properties) {
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    @Around("@within(ignoreTenant) || @annotation(ignoreTenant)")
    public Object around(ProceedingJoinPoint joinPoint, IgnoreTenant ignoreTenant) throws Throwable {
        String accessPoint = resolveAccessPoint(joinPoint, ignoreTenant);
        try (TenantBypassScope ignored = TenantBypassScope.open(accessPoint)) {
            Object result;
            try {
                result = joinPoint.proceed();
            } catch (Throwable ex) {
                // 访问失败同样入审计（FR-004 每次旁路访问）；审计失败不掩盖原始异常
                auditQuietly(accessPoint);
                throw ex;
            }
            // 执行成功后审计（fail-closed：审计失败抛出并阻断结果外达，FR-004 异常分支）
            audit(accessPoint);
            return result;
        }
    }

    private void audit(String accessPoint) {
        if (properties.getAudit().isEnabled()) {
            auditLogger.record(accessPoint);
        }
    }

    private void auditQuietly(String accessPoint) {
        try {
            audit(accessPoint);
        } catch (RuntimeException ignored) {
            // 原始异常优先（FR-004 不得静默丢失已由 record 内 ERROR 告警兜底）
        }
    }

    private static String resolveAccessPoint(ProceedingJoinPoint joinPoint, IgnoreTenant ignoreTenant) {
        if (ignoreTenant != null && !ignoreTenant.value().isBlank()) {
            return ignoreTenant.value();
        }
        return joinPoint.getSignature().getDeclaringTypeName() + "#" + joinPoint.getSignature().getName();
    }
}
