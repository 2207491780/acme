package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户令牌撤销检查测试（Q-D02、FR-004 / BR-006 即时失效；AC-011 撤销命中分支）。
 */
class RevocationCheckFilterTest {

    private final GatewayProperties properties = new GatewayProperties();

    private RevocationCheckFilter filter(UserTokenRevocationChecker checker) {
        return new RevocationCheckFilter(checker, properties.getRevocation());
    }

    private static MockServerWebExchange exchangeWithClaims(UserTokenClaims claims) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/v1/system/users"));
        if (claims != null) {
            exchange.getAttributes().put(ExchangeAttrs.USER_CLAIMS, claims);
        }
        return exchange;
    }

    private static UserTokenClaims claims(String tokenId) {
        return new UserTokenClaims("1", "default", Channel.B, Set.of("super_admin"), "access", tokenId);
    }

    private static GatewayFilterChain pass() {
        return ex -> Mono.empty();
    }

    @Test
    void revokedTokenRejected401() {
        RevocationCheckFilter filter = filter(tokenId -> true);
        assertThatThrownBy(() -> filter.filter(exchangeWithClaims(claims("jti-1")), pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> {
                    GatewayRejectedException rejected = (GatewayRejectedException) ex;
                    assertThat(rejected.httpStatus()).isEqualTo(401);
                    assertThat(rejected.errorCode()).isEqualTo(GatewayError.AUTH_FAILED);
                });
    }

    @Test
    void activeTokenPasses() {
        AtomicInteger calls = new AtomicInteger();
        RevocationCheckFilter filter = filter(tokenId -> {
            calls.incrementAndGet();
            return false;
        });
        filter.filter(exchangeWithClaims(claims("jti-1")), pass()).block();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void publicPathWithoutClaimsSkipsCheck() {
        AtomicInteger calls = new AtomicInteger();
        RevocationCheckFilter filter = filter(tokenId -> {
            calls.incrementAndGet();
            return true;
        });
        // 公开路径：AuthFilter 未写入 claims → 不查撤销
        filter.filter(exchangeWithClaims(null), pass()).block();
        assertThat(calls.get()).isZero();
    }

    @Test
    void checkerErrorFailsOpenByDefault() {
        RevocationCheckFilter filter = filter(tokenId -> {
            throw new IllegalStateException("redis down");
        });
        // 默认 FAIL_OPEN：放行不拒绝
        filter.filter(exchangeWithClaims(claims("jti-1")), pass()).block();
    }

    @Test
    void checkerErrorFailsCloseWhenConfigured() {
        properties.getRevocation().setOnError(RateLimitOnError.FAIL_CLOSE);
        RevocationCheckFilter filter = filter(tokenId -> {
            throw new IllegalStateException("redis down");
        });
        assertThatThrownBy(() -> filter.filter(exchangeWithClaims(claims("jti-1")), pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> assertThat(((GatewayRejectedException) ex).httpStatus()).isEqualTo(401));
    }

    @Test
    void disabledSwitchSkipsCheck() {
        properties.getRevocation().setEnabled(false);
        RevocationCheckFilter filter = filter(tokenId -> true);
        filter.filter(exchangeWithClaims(claims("jti-1")), pass()).block();
    }

    @Test
    void defaultCheckerNeverRevokes() {
        com.acme.framework.gateway.internal.NeverRevokedTokenChecker never =
                new com.acme.framework.gateway.internal.NeverRevokedTokenChecker();
        assertThat(never.isRevoked("jti-1")).isFalse();
        assertThat(never.isRevoked(null)).isFalse();
    }
}
