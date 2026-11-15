package com.acme.framework.gateway.interceptor;

import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.support.UserTokenClaims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 灰度用户选择器（REQ-20261114-120106、AC-5/AC-6、确认记录③）：在鉴权过滤器（200）之后执行，
 * 依赖 AuthFilter 写入的验签 claims 判定命中——用户白名单精确匹配或按比例确定性哈希分桶
 * （SHA-256("acme-gray:" + userId) 模 100，同用户恒定命中/恒定不命中，规则变更前结果稳定）。
 * 命中注入灰度标记头（{@code acme.gateway.gray.header}，默认 {@code X-Acme-Gray}，值 {@code on}），
 * 由既有 SCG 路由谓词（Header 断言）消费（8.6）；未命中与未认证请求不注入。
 * 无论命中与否均先移除客户端伪造的同名入站头（与 8.3 剥离伪造身份头同口径），标记头仅网关可写。
 * 规则读 {@link GatewayProperties.Gray}（逐请求读取，Nacos 配置重绑定后即时生效，确认记录③）。
 */
public final class GrayUserFilter implements GlobalFilter, Ordered {

    /** 标记头固定值（SCG Header 路由谓词按 {@code =on} 消费）。 */
    static final String MARK = "on";

    /** 灰度分桶盐（常量：保证规则集不变时同一用户结果恒定；参与哈希输入防跨场景碰撞）。 */
    private static final String HASH_SALT = "acme-gray:";

    private final GatewayProperties.Gray config;

    public GrayUserFilter(GatewayProperties.Gray config) {
        this.config = config;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!config.isEnabled()) {
            // 灰度关闭：仍剥离伪造标记头（防客户端直接伪造灰度身份），不注入
            return chain.filter(stripMarker(exchange));
        }
        UserTokenClaims claims = (UserTokenClaims) exchange.getAttributes().get(ExchangeAttrs.USER_CLAIMS);
        String userId = claims == null ? null : claims.userId();
        boolean hit = userId != null && !userId.isBlank() && hit(userId);
        // 先剥离伪造标记头（wire 形态 c：未命中请求不携带该头），命中后重写为 on
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.headers(h -> {
                    h.remove(config.getHeader());
                    if (hit) {
                        h.set(config.getHeader(), MARK);
                    }
                }))
                .build();
        return chain.filter(mutated);
    }

    private ServerWebExchange stripMarker(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(builder -> builder.headers(h -> h.remove(config.getHeader())))
                .build();
    }

    /** 命中判定：白名单优先；未命中白名单再按比例确定性哈希（AC-5/AC-6）。 */
    private boolean hit(String userId) {
        if (config.getUsers().contains(userId)) {
            return true;
        }
        return percentHit(userId, config.getPercent());
    }

    /** 确定性哈希分桶（AC-6）：SHA-256 摘要前 8 字节大端序模 100，同用户恒定。 */
    static boolean percentHit(String userId, int percent) {
        if (percent <= 0) {
            return false;
        }
        if (percent >= 100) {
            return true;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((HASH_SALT + userId).getBytes(StandardCharsets.UTF_8));
            long bucket = ((long) (hash[0] & 0xFF) << 56) | ((long) (hash[1] & 0xFF) << 48)
                    | ((long) (hash[2] & 0xFF) << 40) | ((long) (hash[3] & 0xFF) << 32)
                    | ((long) (hash[4] & 0xFF) << 24) | ((long) (hash[5] & 0xFF) << 16)
                    | ((long) (hash[6] & 0xFF) << 8) | (hash[7] & 0xFF);
            return Long.remainderUnsigned(bucket, 100) < percent;
        } catch (NoSuchAlgorithmException ex) {
            // JVM 必含 SHA-256，理论不可达；保守判定为不命中（不注入灰度标记）
            return false;
        }
    }

    /** 过滤链顺序 450：注入（400）之后、请求日志（500）之前——依赖登录身份（AuthFilter 200）。 */
    @Override
    public int getOrder() {
        return 450;
    }
}
