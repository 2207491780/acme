package com.acme.framework.gateway.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.trace.support.TraceScope;
import com.acme.framework.trace.support.Traces;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 网关侧 trace 透传（FR-024、8.0；取舍 #4：复用 Traces/TraceScope，不为 WebFlux 改造 framework-trace）：
 * 入站复用或生成、出站响应同值头、结束清理；traceId 经交换属性交接给请求日志（14.1.1/14.1.2）。
 */
public final class TraceWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        TraceContext ctx = Traces.resolve(
                exchange.getRequest().getHeaders().getFirst(TraceConstants.TRACE_ID_HEADER),
                exchange.getRequest().getHeaders().getFirst(TraceConstants.SPAN_ID_HEADER));
        exchange.getAttributes().put(ExchangeAttrs.TRACE_CONTEXT, ctx);
        exchange.getResponse().getHeaders().set(TraceConstants.TRACE_ID_HEADER, ctx.traceId());
        exchange.getResponse().getHeaders().set(TraceConstants.SPAN_ID_HEADER, ctx.spanId());
        // 复用/生成值透传下游（14.1.2 三通道透传、AC-006 可关联）：请求头携带解析后的 traceId/spanId
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.set(TraceConstants.TRACE_ID_HEADER, ctx.traceId());
                    headers.set(TraceConstants.SPAN_ID_HEADER, ctx.spanId());
                }))
                .build();
        TraceScope scope = TraceScope.open(ctx);
        return chain.filter(mutated).doFinally(signal -> scope.close());
    }

    /** 尽早绑定使 MDC 覆盖面最大（framework-trace TraceHttpFilter 同口径）。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
