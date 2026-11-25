package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import com.acme.framework.security.revocation.RevocationCheckPolicy;
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
 * 用户令牌撤销检查测试（Q-D02、FR-004 / BR-006 即时失效；AC-011 撤销命中分支）+
 * fail-closed 开关（REQ-20261125-001736 AC-7：异常处置统一经 RevocationCheckPolicy，
 * 默认 false=放行 + WARN，true=503 REVOCATION_CHECK_UNAVAILABLE 拒绝）。
 */
class RevocationCheckFilterTest {

    private final GatewayProperties properties = new GatewayProperties();

    private RevocationCheckFilter filter(UserTokenRevocationChecker checker) {
        return new RevocationCheckFilter(checker, properties.getRevocation(), new RevocationCheckPolicy(false));
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
    void should_rejectWith503RevocationCheckUnavailable_when_failClosedPolicyAndCheckerError() {
        RevocationCheckFilter filter = new RevocationCheckFilter(tokenId -> {
            throw new IllegalStateException("redis down");
        }, properties.getRevocation(), new RevocationCheckPolicy(true));

        assertThatThrownBy(() -> filter.filter(exchangeWithClaims(claims("jti-1")), pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> {
                    GatewayRejectedException rejected = (GatewayRejectedException) ex;
                    assertThat(rejected.httpStatus()).isEqualTo(503);
                    assertThat(rejected.errorCode()).isEqualTo(GatewayError.REVOCATION_CHECK_UNAVAILABLE);
                });
    }

    @Test
    void should_keepFailOpen_when_failClosedPolicyAbsentAndCheckerError() {
        RevocationCheckFilter filter = new RevocationCheckFilter(tokenId -> {
            throw new IllegalStateException("redis down");
        }, properties.getRevocation(), new RevocationCheckPolicy(false));
        // 与现状一致：放行 + WARN
        filter.filter(exchangeWithClaims(claims("jti-1")), pass()).block();
    }

    @Test
    void downstreamErrorPropagatesWithSingleForwardOnFailOpen() {
        // review 修复 P1 回归：异常兜底只覆盖撤销判定——下游业务错误原样传播、
        // 不得二次调用 chain.filter（POST 重复副作用）
        RevocationCheckFilter filter = filter(tokenId -> false);
        AtomicInteger forwarded = new AtomicInteger();
        GatewayFilterChain failingChain = ex -> {
            forwarded.incrementAndGet();
            return Mono.error(new IllegalStateException("downstream boom"));
        };
        assertThatThrownBy(() -> filter.filter(exchangeWithClaims(claims("jti-1")), failingChain).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("downstream boom");
        assertThat(forwarded.get()).isEqualTo(1);
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
