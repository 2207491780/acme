package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.common.context.TraceContext;
import com.acme.framework.core.support.ChannelPrefix;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.support.UserTokenClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关请求日志过滤器（FR-024、8.7、14.1.1）：必含 traceId/service/spanId + method/path/status/耗时/端别/主体摘要；
 * 日志失败不影响转发（FR-024 异常分支）。
 */
public final class RequestLogFilter implements GlobalFilter, Ordered {

    /** 网关请求日志 logger 名（8.7）。 */
    public static final String LOGGER_NAME = "acme.gateway.access";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private final String serviceName;

    public RequestLogFilter(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getAttributes().put(ExchangeAttrs.START_TIME, System.currentTimeMillis());
        return chain.filter(exchange).doFinally(signal -> report(exchange));
    }

    private void report(ServerWebExchange exchange) {
        try {
            Long start = exchange.getAttribute(ExchangeAttrs.START_TIME);
            long durationMs = start == null ? -1 : System.currentTimeMillis() - start;
            TraceContext trace = exchange.getAttribute(ExchangeAttrs.TRACE_CONTEXT);
            UserTokenClaims claims = (UserTokenClaims) exchange.getAttribute(ExchangeAttrs.USER_CLAIMS);
            log.info("gateway service={} traceId={} spanId={} method={} path={} status={} durationMs={} channel={} userId={}",
                    serviceName,
                    trace == null ? null : trace.traceId(),
                    trace == null ? null : trace.spanId(),
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath(),
                    exchange.getResponse().getStatusCode(),
                    durationMs,
                    channel(exchange, claims),
                    claims == null ? null : claims.userId());
        } catch (RuntimeException ex) {
            // 日志失败不影响转发（FR-024 异常分支）
        }
    }

    private static Channel channel(ServerWebExchange exchange, UserTokenClaims claims) {
        if (claims != null && claims.channel() != null) {
            return claims.channel();
        }
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith(ChannelPrefix.ADMIN)) {
            return Channel.B;
        }
        if (path.startsWith(ChannelPrefix.APP)) {
            return Channel.C;
        }
        return null;
    }

    /** 过滤链顺序 5（8.1：链路末位，覆盖拒绝路径可观测）。 */
    @Override
    public int getOrder() {
        return 500;
    }
}
