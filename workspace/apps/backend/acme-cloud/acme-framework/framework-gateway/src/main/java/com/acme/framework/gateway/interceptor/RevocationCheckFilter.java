package com.acme.framework.gateway.interceptor;

import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 用户令牌撤销检查过滤器（Q-D02、FR-004 即时失效）：验签（AuthFilter，order 200）之后、
 * 限流 / 注入之前逐请求判定撤销（每请求一查、无本地缓存，Q-D02 取舍 #11）；
 * 检查异常默认 FAIL_OPEN（放行 + WARN，与限流兜底口径一致），可配 FAIL_CLOSED。
 * 撤销命中 → 401 AUTH_FAILED 不向下游转发（BR-006）。
 */
public final class RevocationCheckFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RevocationCheckFilter.class);

    private final UserTokenRevocationChecker checker;
    private final GatewayProperties.Revocation config;

    public RevocationCheckFilter(UserTokenRevocationChecker checker, GatewayProperties.Revocation config) {
        this.checker = checker;
        this.config = config;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        UserTokenClaims claims = (UserTokenClaims) exchange.getAttributes().get(ExchangeAttrs.USER_CLAIMS);
        // 公开 / 未鉴权路径无 claims，不查撤销
        if (claims == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }
        return Mono.fromCallable(() -> checker.isRevoked(claims.tokenId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(revoked -> revoked
                        ? Mono.error(new GatewayRejectedException(GatewayError.AUTH_FAILED))
                        : chain.filter(exchange))
                .onErrorResume(ex -> {
                    if (ex instanceof GatewayRejectedException rejected) {
                        return Mono.error(rejected);
                    }
                    if (config.getOnError() == RateLimitOnError.FAIL_CLOSE) {
                        log.warn("用户令牌撤销检查失败，FAIL_CLOSED 拒绝：tokenId={}", claims.tokenId());
                        return Mono.error(new GatewayRejectedException(GatewayError.AUTH_FAILED));
                    }
                    log.warn("用户令牌撤销检查失败，FAIL_OPEN 放行：tokenId={}", claims.tokenId());
                    return chain.filter(exchange);
                });
    }

    /** 过滤链顺序 250（Q-D02：验签 200 之后、限流 300 / 注入 400 之前）。 */
    @Override
    public int getOrder() {
        return 250;
    }
}
