package com.acme.framework.feign.internal;

import com.acme.framework.feign.support.FeignFallback;
import com.acme.framework.feign.support.FeignIdempotent;
import feign.Capability;
import feign.InvocationHandlerFactory;
import feign.Target;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用层装饰（internal，Q-D004）：{@code @FeignIdempotent} 打标（FR-019、取舍 #9）+
 * 降级 SPI 接线（FR-020：仅基础设施失败兜底，业务错误透传）；传输异常映射 {@code BizException}（FR-021）。
 * 降级边界：熔断 {@code CIRCUIT_OPEN}/超时/IO 传输失败走 fallback；{@code BizException} 一律透传；
 * 其余未知异常默认透传不降级（不掩盖业务错误）。
 */
public class FeignInvocationCapability implements Capability {

    private final ObjectProvider<FeignFallback> fallbackProvider;

    public FeignInvocationCapability(ObjectProvider<FeignFallback> fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public InvocationHandlerFactory enrich(InvocationHandlerFactory factory) {
        return (target, dispatch) -> {
            Map<Method, InvocationHandlerFactory.MethodHandler> decorated = new LinkedHashMap<>();
            dispatch.forEach((method, handler) ->
                    decorated.put(method, decorate(target, method, handler)));
            return factory.create(target, decorated);
        };
    }

    private InvocationHandlerFactory.MethodHandler decorate(Target<?> target, Method method,
                                                            InvocationHandlerFactory.MethodHandler handler) {
        return args -> {
            FeignIdempotent annotation = method.getAnnotation(FeignIdempotent.class);
            if (annotation != null) {
                IdempotentMarkers.mark(annotation.value());
            }
            try {
                return handler.invoke(args);
            } catch (Throwable ex) {
                RuntimeException mapped = FeignErrors.map(ex);
                if (!FeignErrors.isInfrastructureFailure(mapped)) {
                    // 业务 BizException / 未知异常：透传不降级（FR-020）
                    throw mapped;
                }
                FeignFallback fallback = fallbackProvider.getIfAvailable();
                if (fallback != null) {
                    return fallback.fallback(target.name(), method.getName(), args, mapped);
                }
                throw mapped;
            } finally {
                IdempotentMarkers.clear();
            }
        };
    }
}
