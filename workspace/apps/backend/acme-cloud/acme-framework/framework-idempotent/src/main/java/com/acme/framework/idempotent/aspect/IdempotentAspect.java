package com.acme.framework.idempotent.aspect;

import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.idempotent.config.IdempotentProperties;
import com.acme.framework.idempotent.internal.HttpIdempotentGate;
import com.acme.framework.idempotent.support.Idempotent;
import com.acme.framework.idempotent.support.IdempotentError;
import com.acme.framework.idempotent.support.MissingTokenPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * HTTP token 入口幂等切面（FR-001/AC-001/002）：取键（注解 SpEL 业务键，缺省取幂等 token 头）
 * → {@link HttpIdempotentGate} 认领/回放；token 缺失按策略（默认 REJECT，取舍 #8）。
 * 幂等键 = {@code KeyComposer.compose("idem","http",token)}（12.3.2 服务前缀）。
 */
@Aspect
public class IdempotentAspect {

    private final HttpIdempotentGate gate;
    private final KeyComposer keyComposer;
    private final IdempotentProperties properties;
    private final ExpressionParser parser = new SpelExpressionParser();

    public IdempotentAspect(HttpIdempotentGate gate, KeyComposer keyComposer, IdempotentProperties properties) {
        this.gate = gate;
        this.keyComposer = keyComposer;
        this.properties = properties;
    }

    /** 注解幂等（FR-001）。 */
    @Around("@annotation(idempotent)")
    public Object guard(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String token = resolveKey(joinPoint, idempotent);
        if (token == null || token.isBlank()) {
            MissingTokenPolicy policy = idempotent.missingTokenPolicy() == null
                    ? properties.getMissingTokenPolicy() : idempotent.missingTokenPolicy();
            if (policy == MissingTokenPolicy.PASS) {
                return joinPoint.proceed();
            }
            throw new BizException(IdempotentError.MISSING_TOKEN);
        }
        String key = keyComposer.compose("idem", "http", token);
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Callable<Object> action = () -> {
            try {
                return joinPoint.proceed();
            } catch (RuntimeException | Error ex) {
                throw ex;
            } catch (Throwable ex) {
                throw new IllegalStateException("业务执行失败", ex);
            }
        };
        @SuppressWarnings("unchecked")
        Class<Object> returnType = (Class<Object>) method.getReturnType();
        return gate.execute(key, action, returnType, idempotent.inFlightPolicy());
    }

    /** 取键：注解 SpEL 业务键优先，缺省取头 {@code X-Acme-Idempotent-Token}。 */
    private String resolveKey(ProceedingJoinPoint joinPoint, Idempotent idempotent) {
        if (idempotent.key() != null && !idempotent.key().isBlank()) {
            return eval(joinPoint, idempotent.key());
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        return request.getHeader(properties.getHeaderName());
    }

    private String eval(ProceedingJoinPoint joinPoint, String expression) {
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            Object[] args = joinPoint.getArgs();
            String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
                if (names != null && i < names.length) {
                    context.setVariable(names[i], args[i]);
                }
            }
            Object value = parser.parseExpression(expression).getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
