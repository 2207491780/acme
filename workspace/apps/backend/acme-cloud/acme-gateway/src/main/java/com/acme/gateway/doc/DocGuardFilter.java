package com.acme.gateway.doc;

import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 聚合文档关闭守卫（REQ-20261114-120106、AC-1）：{@code acme.doc.enabled=false} 时对
 * {@code /v3/api-docs/**} 前置拒绝——404 + DOC_DISABLED（网关标准错误信封），请求不进入负载均衡转发，
 * 对外表现为文档端点不存在（路由不暴露）。置于过滤链最前（先于剥头/鉴权/XSS 清洗），
 * 关闭态不付出任何后续链路成本。
 */
public final class DocGuardFilter implements GlobalFilter, Ordered {

    /** 聚合文档转发路径前缀（与 application.yml doc 路由谓词一致）。 */
    static final String API_DOCS_PREFIX = "/v3/api-docs";

    private final GatewayDocProperties properties;

    public DocGuardFilter(GatewayDocProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()
                && exchange.getRequest().getURI().getPath().startsWith(API_DOCS_PREFIX)) {
            return Mono.error(new GatewayRejectedException(GatewayError.DOC_DISABLED));
        }
        return chain.filter(exchange);
    }

    /** 过滤链顺序 50：先于剥离伪造头（100），关闭态最早拒绝。 */
    @Override
    public int getOrder() {
        return 50;
    }
}
