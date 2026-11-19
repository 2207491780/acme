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
import org.springframework.expression.BeanResolver;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

/**
 * 审计埋点切面（FR-008/010、AC-008）：声明方法执行 → 公共上下文（操作人/租户/端别）+ traceId 关联（14.1.1）
 * → 按注解白名单取参数摘要（截取+脱敏）→ 产生 {@link AuditEvent}；
 * 未声明方法不产生记录；埋点失败不阻断业务主流程（BR-013），ERROR 结构化日志兜底。
 * REQ-20261117-061017（确认③）：{@code beforeQuery} 旧值快照——proceed 前求值（beanResolver 支持
 * {@code @beanName} 引用），结果 JSON（Jackson 可用时）/toString 截断 512 后以 {@code before=<摘要>}
 * 前缀并入 content；求值/序列化失败静默跳过。
 */
@Aspect
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    /** before 摘要截断上限（REQ-20261117-061017 确认③：审计内容 512 先例同口径）。 */
    static final int BEFORE_SUMMARY_MAX_LENGTH = 512;

    /** before 前缀与参数摘要的分隔（确认③ {@code before=<摘要>; <既有 content>}）。 */
    private static final String BEFORE_PREFIX = "before=";

    private static final String BEFORE_SEPARATOR = "; ";

    private final AuditEventPublisher publisher;
    private final AuditProperties properties;
    private final BeanResolver beanResolver;
    private final JsonMapper jsonMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();

    public AuditAspect(AuditEventPublisher publisher, AuditProperties properties) {
        this(publisher, properties, null, null);
    }

    /** REQ-20261117-061017 装配面（tester AMB-2 三参构造口径；无 Jackson 时回退 toString）。 */
    public AuditAspect(AuditEventPublisher publisher, AuditProperties properties, BeanResolver beanResolver) {
        this(publisher, properties, beanResolver, null);
    }

    public AuditAspect(AuditEventPublisher publisher, AuditProperties properties,
                       BeanResolver beanResolver, JsonMapper jsonMapper) {
        this.publisher = publisher;
        this.properties = properties;
        this.beanResolver = beanResolver;
        this.jsonMapper = jsonMapper;
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

    /**
     * 内容摘要（确认③）：{@code before=<旧值摘要>; <既有参数摘要>}；before 缺失时维持既有形态
     * （白名单参数 → {@code name=value}，截取 + 脱敏 FR-010/BR-013）。
     */
    private String content(ProceedingJoinPoint joinPoint, AuditLog auditLog) {
        String base = baseContent(joinPoint, auditLog);
        String before = beforeSummary(joinPoint, auditLog);
        if (before == null) {
            return base;
        }
        String combined = BEFORE_PREFIX + before
                + (base == null || base.isBlank() ? "" : BEFORE_SEPARATOR + base);
        return AuditContentTruncator.apply(combined,
                properties.getContentMaxLength(),
                properties.isMaskSensitive() && auditLog.mask());
    }

    /** 既有内容摘要：白名单参数 → {@code name=value}（beforeQuery 未声明时即全部 content）。 */
    private String baseContent(ProceedingJoinPoint joinPoint, AuditLog auditLog) {
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

    /**
     * 旧值快照（确认③）：proceed 前求值——beanResolver 支持 {@code @beanName} 引用（缺省装配无
     * resolver 时 {@code @beanName} 静默失败）；结果 JSON（Jackson 可用）/toString 截断 512；
     * 求值/序列化失败静默跳过（返回 null，不产 before 片段）。
     */
    private String beforeSummary(ProceedingJoinPoint joinPoint, AuditLog auditLog) {
        String expression = auditLog.beforeQuery();
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Object value = parser.parseExpression(expression)
                    .getValue(beforeEvaluationContext(joinPoint));
            if (value == null) {
                return null;
            }
            return AuditContentTruncator.apply(serialize(value), BEFORE_SUMMARY_MAX_LENGTH,
                    properties.isMaskSensitive() && auditLog.mask());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** beforeQuery 求值上下文（变量同既有 p0/参数名/a0；beanResolver 可选）。 */
    private StandardEvaluationContext beforeEvaluationContext(ProceedingJoinPoint joinPoint) {
        return evaluationContext(joinPoint, true);
    }

    /**
     * SpEL 求值上下文（S14 收敛 beforeQuery 与 targetType/targetId/白名单两处同款变量装配）：
     * 变量 p0/a0/参数名；{@code withBeanResolver} 仅 beforeQuery 启用（{@code @beanName} 引用
     * 确认③口径，其余表达式维持原「无 resolver」语义不变）。
     */
    private StandardEvaluationContext evaluationContext(ProceedingJoinPoint joinPoint, boolean withBeanResolver) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (withBeanResolver && beanResolver != null) {
            context.setBeanResolver(beanResolver);
        }
        Object[] args = joinPoint.getArgs();
        String[] paramNames = names.getParameterNames(((MethodSignature) joinPoint.getSignature()).getMethod());
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
            if (paramNames != null && i < paramNames.length) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        return context;
    }

    /** 快照序列化（确认③）：Jackson 可用优先 JSON，失败回退 toString。 */
    private String serialize(Object value) {
        if (jsonMapper != null) {
            try {
                return jsonMapper.writeValueAsString(value);
            } catch (RuntimeException ignored) {
                // 序列化失败回退 toString
            }
        }
        return String.valueOf(value);
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
            Object value = parser.parseExpression(expression).getValue(evaluationContext(joinPoint, false));
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
