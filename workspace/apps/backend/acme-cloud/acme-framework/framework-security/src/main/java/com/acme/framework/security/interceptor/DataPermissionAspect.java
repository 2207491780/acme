package com.acme.framework.security.interceptor;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.security.support.DataPermission;
import com.acme.framework.security.support.DataPermissionContext;
import com.acme.framework.security.support.DataPermissionDecision;
import com.acme.framework.security.support.DataPermissionEvaluator;
import com.acme.framework.security.support.SecurityError;
import com.acme.common.core.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * 数据权限拦截切面（FR-017、Q-D09）：拦截 {@link DataPermission} 标注方法；
 * 无评估器=放行（Q-004）；评估器异常→按拒绝处理（FR-017 异常分支）；拒绝→标准失败响应（7.5，403）。
 */
@Aspect
public class DataPermissionAspect {

    private final ObjectProvider<DataPermissionEvaluator> evaluatorProvider;

    public DataPermissionAspect(ObjectProvider<DataPermissionEvaluator> evaluatorProvider) {
        this.evaluatorProvider = evaluatorProvider;
    }

    @Around("@annotation(dataPermission)")
    public Object intercept(ProceedingJoinPoint joinPoint, DataPermission dataPermission) throws Throwable {
        DataPermissionEvaluator evaluator;
        try {
            evaluator = evaluatorProvider.getIfAvailable();
        } catch (IllegalStateException ex) {
            // Q-004：评估器唯一契约；多实现属装配错误，显式失败不静默选一
            throw new IllegalStateException("DataPermissionEvaluator 仅允许注册一个实现（Q-004）", ex);
        }
        if (evaluator != null) {
            DataPermissionDecision decision = decide(evaluator, joinPoint, dataPermission);
            if (decision == DataPermissionDecision.DENY) {
                throw new BizException(SecurityError.DATA_PERMISSION_DENIED);
            }
        }
        return joinPoint.proceed();
    }

    private DataPermissionDecision decide(DataPermissionEvaluator evaluator,
                                          ProceedingJoinPoint joinPoint, DataPermission dataPermission) {
        try {
            UserContext userContext = UserContext.get();
            TenantContext tenantContext = TenantContext.get();
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            DataPermissionContext context = new DataPermissionContext(
                    dataPermission.value(),
                    method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                    userContext == null ? null : userContext.userId(),
                    tenantContext == null ? null : tenantContext.tenantId(),
                    userContext == null ? Set.of() : userContext.roles());
            DataPermissionDecision decision = evaluator.evaluate(context);
            return decision == null ? DataPermissionDecision.DENY : decision;
        } catch (RuntimeException ex) {
            // 评估器异常→按拒绝处理（FR-017 异常分支）
            return DataPermissionDecision.DENY;
        }
    }
}
