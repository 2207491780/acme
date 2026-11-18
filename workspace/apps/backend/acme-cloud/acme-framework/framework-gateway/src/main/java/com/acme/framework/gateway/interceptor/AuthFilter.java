package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.ChannelPrefix;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenVerifier;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 统一鉴权与验签过滤器（FR-019、8.2）：按端独立策略（BR-014、Backend.md 11.1.3）；
 * 验签失败/过期→401（Q-D11），越端（channel claim 与前缀不符）→403（FR-019 异常分支）；
 * 公开路径与非双端前缀路径放行且不认证。
 */
public final class AuthFilter implements GlobalFilter, Ordered {

    private final UserTokenVerifier verifier;
    private final GatewayProperties.Auth auth;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthFilter(UserTokenVerifier verifier, GatewayProperties.Auth auth) {
        this.verifier = verifier;
        this.auth = auth;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        Channel channel = channelOf(path);
        if (channel == null) {
            return chain.filter(exchange);
        }
        GatewayProperties.EndpointPolicy policy = channel == Channel.B ? auth.getB() : auth.getC();
        if (!policy.isEnabled() || isPublic(policy, path)) {
            return chain.filter(exchange);
        }
        UserTokenClaims claims;
        try {
            claims = verifier.verify(extractToken(exchange));
        } catch (BizException ex) {
            // 验签失败/过期 → 网关标准错误（8.2、Q-D11）
            return Mono.error(new GatewayRejectedException(ex.errorCode()));
        }
        // 刷新令牌不得鉴权 API（11.4 会话策略）：tokenType 必须为访问令牌
        if (!"access".equals(claims.tokenType())) {
            return Mono.error(new GatewayRejectedException(GatewayError.AUTH_FAILED));
        }
        // 越端判定（FR-019 异常分支、11.1.1）：channel claim 缺失或与路由前缀语义不符 → 403
        if (claims.channel() == null || claims.channel() != channel) {
            return Mono.error(new GatewayRejectedException(GatewayError.CHANNEL_MISMATCH));
        }
        exchange.getAttributes().put(ExchangeAttrs.USER_CLAIMS, claims);
        return chain.filter(exchange);
    }

    /** 端别取自路由前缀（Backend.md 11.1.3）；非双端前缀不鉴权。 */
    private static Channel channelOf(String path) {
        if (path.startsWith(ChannelPrefix.ADMIN)) {
            return Channel.B;
        }
        if (path.startsWith(ChannelPrefix.APP)) {
            return Channel.C;
        }
        return null;
    }

    private boolean isPublic(GatewayProperties.EndpointPolicy policy, String path) {
        for (String pattern : policy.getPublicPaths()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 用户令牌取自配置头（默认 Authorization: Bearer）。 */
    private String extractToken(ServerWebExchange exchange) {
        String raw = exchange.getRequest().getHeaders().getFirst(auth.getTokenHeader());
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value;
    }

    /** 过滤链顺序 2（8.1：剥离伪造头之后）。 */
    @Override
    public int getOrder() {
        return 200;
    }
}
