package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.internal.RateLimiters;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.core.support.RateLimitKeyBuilder;
import com.acme.framework.gateway.support.UserTokenClaims;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流过滤器测试（FR-022、Q-D07、WQ-005 兜底键、AC-015/017）。
 */
class RateLimitFilterTest {

    private GatewayProperties.Ratelimit config(int primaryQuota, int fallbackQuota) {
        GatewayProperties.Ratelimit config = new GatewayProperties.Ratelimit();
        config.getB().setQuotaPerMinute(primaryQuota);
        config.getFallback().setQuotaPerMinute(fallbackQuota);
        return config;
    }

    private RateLimitFilter filter(GatewayProperties.Ratelimit config) {
        return new RateLimitFilter(new RateLimiters(config), new com.acme.framework.core.support.DefaultRateLimitKeyBuilder(), config);
    }

    private static GatewayFilterChain pass() {
        return exchange -> Mono.empty();
    }

    @Test
    void authenticatedRequestUsesPrimaryKey() {
        GatewayProperties.Ratelimit config = config(1, 100);
        RateLimitFilter filter = filter(config);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
        exchange.getAttributes().put(ExchangeAttrs.USER_CLAIMS,
                new UserTokenClaims("u-1", "t-1", Channel.B, Set.of(), "access", "jti"));
        filter.filter(exchange, pass()).block();
        // 第二个不同账号不受第一个账号配额影响（主键=账号+接口）
        MockServerWebExchange other = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
        other.getAttributes().put(ExchangeAttrs.USER_CLAIMS,
                new UserTokenClaims("u-2", "t-1", Channel.B, Set.of(), "access", "jti"));
        filter.filter(other, pass()).block();
    }

    @Test
    void authenticatedRequestRejected429WhenQuotaExhausted() {
        GatewayProperties.Ratelimit config = config(1, 100);
        RateLimitFilter filter = filter(config);
        MockServerWebExchange first = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
        first.getAttributes().put(ExchangeAttrs.USER_CLAIMS,
                new UserTokenClaims("u-1", "t-1", Channel.B, Set.of(), "access", "jti"));
        filter.filter(first, pass()).block();
        MockServerWebExchange second = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
        second.getAttributes().put(ExchangeAttrs.USER_CLAIMS,
                new UserTokenClaims("u-1", "t-1", Channel.B, Set.of(), "access", "jti"));
        assertThatThrownBy(() -> filter.filter(second, pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> {
                    GatewayRejectedException rejected = (GatewayRejectedException) ex;
                    assertThat(rejected.errorCode()).isEqualTo(GatewayError.RATE_LIMITED);
                    assertThat(rejected.httpStatus()).isEqualTo(429);
                });
    }

    @Test
    void unauthenticatedRequestUsesFallbackKey() {
        // 兜底配额 1：同 IP+接口 第二次被拒 429（WQ-005）
        GatewayProperties.Ratelimit config = config(100, 1);
        RateLimitFilter filter = filter(config);
        MockServerWebExchange first = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/sample/test-token"));
        filter.filter(first, pass()).block();
        MockServerWebExchange second = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/sample/test-token"));
        assertThatThrownBy(() -> filter.filter(second, pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> assertThat(((GatewayRejectedException) ex).httpStatus()).isEqualTo(429));
    }

    @Test
    void fallbackKeySeparatesIps() {
        GatewayProperties.Ratelimit config = config(100, 1);
        RateLimitFilter filter = filter(config);
        MockServerHttpRequest requestA = MockServerHttpRequest.get("/public/x")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1234)).build();
        MockServerWebExchange a = MockServerWebExchange.from(requestA);
        filter.filter(a, pass()).block();
        MockServerHttpRequest requestB = MockServerHttpRequest.get("/public/x")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 1234)).build();
        MockServerWebExchange b = MockServerWebExchange.from(requestB);
        filter.filter(b, pass()).block();
    }

    @Test
    void fallbackDisabledPassesThrough() {
        GatewayProperties.Ratelimit config = config(100, 1);
        config.getFallback().setEnabled(false);
        RateLimitFilter filter = filter(config);
        for (int i = 0; i < 3; i++) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/public/x")), pass()).block();
        }
    }

    @Test
    void failOpenPassesThroughOnKeyBuilderFailure() {
        GatewayProperties.Ratelimit config = config(100, 100);
        RateLimitKeyBuilder broken = new RateLimitKeyBuilder() {
            @Override
            public String build(com.acme.framework.core.support.RateLimitKeyInput input) {
                throw new IllegalStateException("broken");
            }

            @Override
            public String buildFallback(com.acme.framework.core.support.RateLimitFallbackInput input) {
                throw new IllegalStateException("broken");
            }
        };
        RateLimitFilter filter = new RateLimitFilter(new RateLimiters(config), broken, config);
        // FAIL_OPEN（默认）：判定异常放行
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/public/x")), pass()).block();
    }

    @Test
    void failCloseRejectsOnKeyBuilderFailure() {
        GatewayProperties.Ratelimit config = config(100, 100);
        config.setOnError(com.acme.framework.core.support.RateLimitOnError.FAIL_CLOSE);
        RateLimitKeyBuilder broken = new RateLimitKeyBuilder() {
            @Override
            public String build(com.acme.framework.core.support.RateLimitKeyInput input) {
                throw new IllegalStateException("broken");
            }

            @Override
            public String buildFallback(com.acme.framework.core.support.RateLimitFallbackInput input) {
                throw new IllegalStateException("broken");
            }
        };
        RateLimitFilter filter = new RateLimitFilter(new RateLimiters(config), broken, config);
        assertThatThrownBy(() -> filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/public/x")), pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> assertThat(((GatewayRejectedException) ex).httpStatus()).isEqualTo(429));
    }
}
