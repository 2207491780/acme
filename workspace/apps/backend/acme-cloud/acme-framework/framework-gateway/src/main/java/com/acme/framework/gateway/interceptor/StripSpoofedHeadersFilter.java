package com.acme.framework.gateway.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 剥离伪造身份头（FR-020、M-10、8.3）：入站 {@code X-Acme-Token} 与信任身份头无条件剥离（无论鉴权通过与否）；
 * DEVICE_ID/APP_VERSION 为客户端事实头，净化（长度/字符集）后重新注入（取舍 #5）；trace 头不剥离（14.1.2）。
 */
public final class StripSpoofedHeadersFilter implements GlobalFilter, Ordered {

    private static final int DEVICE_ID_MAX = 64;
    private static final int APP_VERSION_MAX = 32;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String deviceId = sanitize(exchange.getRequest().getHeaders().getFirst(ServiceHeaders.DEVICE_ID), DEVICE_ID_MAX);
        String appVersion = sanitize(exchange.getRequest().getHeaders().getFirst(ServiceHeaders.APP_VERSION), APP_VERSION_MAX);
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.remove(ServiceHeaders.SERVICE_TOKEN);
                    headers.remove(ServiceHeaders.USER_ID);
                    headers.remove(ServiceHeaders.TENANT_ID);
                    headers.remove(ServiceHeaders.CHANNEL);
                    headers.remove(ServiceHeaders.OPERATOR);
                    headers.remove(ServiceHeaders.DEVICE_ID);
                    headers.remove(ServiceHeaders.APP_VERSION);
                    if (deviceId != null) {
                        headers.set(ServiceHeaders.DEVICE_ID, deviceId);
                    }
                    if (appVersion != null) {
                        headers.set(ServiceHeaders.APP_VERSION, appVersion);
                    }
                }))
                .build();
        // 属性表为 ConcurrentHashMap：null 值不得放入（BR-025 交接位仅存非空值）
        if (deviceId != null) {
            mutated.getAttributes().put(ExchangeAttrs.DEVICE_ID, deviceId);
        }
        if (appVersion != null) {
            mutated.getAttributes().put(ExchangeAttrs.APP_VERSION, appVersion);
        }
        return chain.filter(mutated);
    }

    /** 净化（8.3）：长度/字符集；非法值丢弃。 */
    static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            value = value.substring(0, maxLength);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ':';
            if (!ok) {
                return null;
            }
        }
        return value;
    }

    /** 过滤链顺序 1（8.1：最先剥离，无论鉴权通过与否）。 */
    @Override
    public int getOrder() {
        return 100;
    }
}
