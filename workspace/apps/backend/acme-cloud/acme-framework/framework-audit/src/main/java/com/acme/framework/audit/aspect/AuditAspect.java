package com.acme.framework.audit.aspect;

import com.acme.common.context.ClientContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import com.acme.framework.audit.config.AuditProperties;
import com.acme.framework.audit.internal.AuditContentTruncator;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditEventPublisher;
import com.acme.framework.audit.support.AuditLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

/**
 * 审计埋点切面（FR-008/010、AC-008）：声明方法执行 → 公共上下文（操作人/租户/端别）+ traceId 关联（14.1.1）
 * → 按注解白名单取参数摘要（截取+脱敏）→ 产生 {@link AuditEvent}；
 * 未声明方法不产生记录；埋点失败不阻断业务主流程（BR-013），ERROR 结构化日志兜底。
 */
@Aspect
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditEventPublisher publisher;
    private final AuditProperties properties;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();

    public AuditAspect(AuditEventPublisher publisher, AuditProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    /** 声明方法执行产生审计记录（FR-008）。 */
    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        try {
            publish(joinPoint, auditLog);
        } catch (RuntimeException ex) {
            log.error("审计埋点失败（不影响业务）: {}", ex.getMessage());
        }
        return joinPoint.proceed();
    }

    private void publish(ProceedingJoinPoint joinPoint, AuditLog auditLog) {
        TraceContext trace = TraceContext.get();
        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                trace == null ? null : trace.traceId(),
                trace == null ? null : trace.spanId(),
                TenantContext.get() == null ? null : TenantContext.get().tenantId(),
                UserContext.get() == null ? null : UserContext.get().userId(),
                ClientContext.get() == null || ClientContext.get().channel() == null
                        ? null : ClientContext.get().channel().name(),
                auditLog.action(),
                eval(joinPoint, auditLog.targetType()),
                eval(joinPoint, auditLog.targetId()),
                content(joinPoint, auditLog),
                Instant.now(),
                "aspect");
        publisher.publish(event);
    }

    /** 内容摘要：白名单参数 → {@code name=value}，截取 + 脱敏（FR-010、BR-013）。 */
    private String content(ProceedingJoinPoint joinPoint, AuditLog auditLog) {
        if (auditLog.contentParams().length == 0) {
            return null;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] paramNames = names.getParameterNames(method);
        Object[] args = joinPoint.getArgs();
        StringBuilder builder = new StringBuilder();
        for (String declared : auditLog.contentParams()) {
            Object value = resolveParam(declared, paramNames, args, joinPoint);
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(declared).append('=').append(value);
        }
        return AuditContentTruncator.apply(builder.toString(),
                properties.getContentMaxLength(),
                properties.isMaskSensitive() && auditLog.mask());
    }

    private Object resolveParam(String declared, String[] paramNames, Object[] args, ProceedingJoinPoint joinPoint) {
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (declared.equals(paramNames[i]) && i < args.length) {
                    return args[i];
                }
            }
        }
        return eval(joinPoint, declared);
    }

    /** SpEL 取值（targetType/targetId/白名单项可为 SpEL）；解析失败按缺失记录，不伪造值（8.2）。 */
    private String eval(ProceedingJoinPoint joinPoint, String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            Object[] args = joinPoint.getArgs();
            String[] paramNames = names.getParameterNames(((MethodSignature) joinPoint.getSignature()).getMethod());
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
                if (paramNames != null && i < paramNames.length) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            Object value = parser.parseExpression(expression).getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
