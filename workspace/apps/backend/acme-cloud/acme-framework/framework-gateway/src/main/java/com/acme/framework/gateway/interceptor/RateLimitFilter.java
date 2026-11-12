package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.ChannelPrefix;
import com.acme.framework.core.support.RateLimitFallbackInput;
import com.acme.framework.core.support.RateLimitKeyBuilder;
import com.acme.framework.core.support.RateLimitKeyInput;
import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.internal.RateLimiters;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 限流过滤器（FR-022、8.5、Q-D07）：按端独立配额（BR-014），key 经 {@link RateLimitKeyBuilder} 统一构造（BR-012）；
 * 公开端点/未鉴权流量走兜底键（IP+接口，WQ-005，与 3.1.3 主键口径并行）；
 * 判定异常兜底可配（默认 FAIL_OPEN，取舍 #13）；限流拒绝→429 RATE_LIMITED（Q-D11）。
 */
public final class RateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimiters limiters;
    private final RateLimitKeyBuilder keyBuilder;
    private final RateLimitOnError onError;
    private final GatewayProperties.Ratelimit config;

    public RateLimitFilter(RateLimiters limiters, RateLimitKeyBuilder keyBuilder,
                           GatewayProperties.Ratelimit config) {
        this.limiters = limiters;
        this.keyBuilder = keyBuilder;
        this.onError = config.getOnError();
        this.config = config;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        UserTokenClaims claims = (UserTokenClaims) exchange.getAttributes().get(ExchangeAttrs.USER_CLAIMS);
        if (claims == null) {
            return filterFallback(exchange, chain);
        }
        RateLimitKeyInput input = new RateLimitKeyInput(
                servicePrefix(exchange),
                claims.channel() != null ? claims.channel() : channelOf(exchange),
                claims.userId(),
                (String) exchange.getAttributes().get(ExchangeAttrs.DEVICE_ID),
                claims.userId(),
                exchange.getRequest().getURI().getPath());
        try {
            if (!limiters.tryAcquire(keyBuilder.build(input), input.channel())) {
                return Mono.error(new GatewayRejectedException(GatewayError.RATE_LIMITED));
            }
        } catch (RuntimeException ex) {
            if (onError == RateLimitOnError.FAIL_CLOSE) {
                return Mono.error(new GatewayRejectedException(GatewayError.RATE_LIMITED));
            }
            // FAIL_OPEN：可用性优先（取舍 #13）
        }
        return chain.filter(exchange);
    }

    /** 兜底限流（WQ-005）：公开端点/未鉴权流量按 IP+接口 计数，拒绝→429。 */
    private Mono<Void> filterFallback(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!config.getFallback().isEnabled()) {
            return chain.filter(exchange);
        }
        RateLimitFallbackInput input = new RateLimitFallbackInput(
                servicePrefix(exchange),
                clientIp(exchange),
                exchange.getRequest().getURI().getPath());
        try {
            if (!limiters.tryAcquireFallback(keyBuilder.buildFallback(input))) {
                return Mono.error(new GatewayRejectedException(GatewayError.RATE_LIMITED));
            }
        } catch (RuntimeException ex) {
            if (onError == RateLimitOnError.FAIL_CLOSE) {
                return Mono.error(new GatewayRejectedException(GatewayError.RATE_LIMITED));
            }
            // FAIL_OPEN：可用性优先（取舍 #13）
        }
        return chain.filter(exchange);
    }

    /** 来源 IP（WQ-005）：取直连远端地址（网关为信任边界，不采信可伪造的转发头）。 */
    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }

    /** 服务前缀取路由标识（12.3.2；静态路由形态，取舍 #12）。 */
    private static String servicePrefix(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? "" : route.getId();
    }

    private static Channel channelOf(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        return path.startsWith(ChannelPrefix.APP) ? Channel.C : Channel.B;
    }

    /** 过滤链顺序 3（8.1：鉴权之后、注入之前——被限流流量不付出签发成本）。 */
    @Override
    public int getOrder() {
        return 300;
    }
}
