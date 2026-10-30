package com.acme.framework.gateway.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.security.support.InternalTokenIssuer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 注入内部令牌与身份头过滤器（FR-021、8.4、11.6.1）：验签通过后注入 {@code X-Acme-Token}（7.7 JWS）与
 * 身份头（值来自验签 claims 与净化后的客户端事实）；注入要素缺失（userId）→拒绝转发（FR-021 异常分支）。
 * 签发经 framework-security {@link InternalTokenIssuer} 接缝（Q-D001 归位，21.1）；
 * Issuer 缺失（acme.security.enabled=false）时本过滤器整体跳过注入（Modules.md 3.5 开关独立性）。
 */
public final class InjectIdentityFilter implements GlobalFilter, Ordered {

    private final ObjectProvider<InternalTokenIssuer> issuer;

    public InjectIdentityFilter(ObjectProvider<InternalTokenIssuer> issuer) {
        this.issuer = issuer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        UserTokenClaims claims = (UserTokenClaims) exchange.getAttributes().get(ExchangeAttrs.USER_CLAIMS);
        if (claims == null) {
            // 公开/未鉴权路径不注入（8.4：验签通过后注入）
            return chain.filter(exchange);
        }
        InternalTokenIssuer tokenIssuer = issuer.getIfAvailable();
        if (tokenIssuer == null) {
            // security 开关关闭：注入能力与未引入一致（3.5），仅转发身份头
            return chain.filter(exchange);
        }
        if (claims.userId() == null || claims.userId().isBlank()) {
            return Mono.error(new GatewayRejectedException(GatewayError.AUTH_FAILED));
        }
        String internalToken = tokenIssuer.issue(new AuthenticatedSubject(
                claims.userId(), claims.tenantId(), claims.channel(), claims.roles()));
        String deviceId = (String) exchange.getAttributes().get(ExchangeAttrs.DEVICE_ID);
        String appVersion = (String) exchange.getAttributes().get(ExchangeAttrs.APP_VERSION);
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.set(ServiceHeaders.SERVICE_TOKEN, internalToken);
                    headers.set(ServiceHeaders.USER_ID, claims.userId());
                    if (claims.tenantId() != null) {
                        headers.set(ServiceHeaders.TENANT_ID, claims.tenantId());
                    }
                    if (claims.channel() != null) {
                        headers.set(ServiceHeaders.CHANNEL, claims.channel().name());
                    }
                    if (deviceId != null) {
                        headers.set(ServiceHeaders.DEVICE_ID, deviceId);
                    }
                    if (appVersion != null) {
                        headers.set(ServiceHeaders.APP_VERSION, appVersion);
                    }
                }))
                .build();
        return chain.filter(mutated);
    }

    /** 过滤链顺序 4（8.1：鉴权、限流之后——被限流流量不付出签发成本）。 */
    @Override
    public int getOrder() {
        return 400;
    }
}
