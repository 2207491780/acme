package com.acme.framework.gateway.interceptor;

import com.acme.common.context.Channel;
import com.acme.framework.gateway.config.GatewayProperties;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.gateway.support.UserTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按端鉴权与越端判定测试（FR-019、8.2、BR-014、AC-015）。
 */
class AuthFilterTest {

    private final GatewayProperties.Auth auth = new GatewayProperties.Auth();

    private AuthFilter filter(UserTokenVerifier verifier) {
        return new AuthFilter(verifier, auth);
    }

    private static UserTokenClaims claims(Channel channel) {
        return new UserTokenClaims("u-1", "t-1", channel, Set.of("admin"), "access", "jti");
    }

    private static GatewayFilterChain pass() {
        return exchange -> Mono.empty();
    }

    @Test
    void validTokenAuthenticatesAndPasses() {
        AuthFilter filter = filter(token -> claims(Channel.B));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/sample/items").header("Authorization", "Bearer tok"));
        filter.filter(exchange, pass()).block();
        assertThat((Object) exchange.getAttribute(ExchangeAttrs.USER_CLAIMS)).isNotNull();
    }

    @Test
    void missingTokenRejected401() {
        AuthFilter filter = filter(token -> {
            throw new com.acme.common.core.BizException(GatewayError.AUTH_FAILED);
        });
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
        assertThatThrownBy(() -> filter.filter(exchange, pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> assertThat(((GatewayRejectedException) ex).httpStatus()).isEqualTo(401));
    }

    @Test
    void crossChannelRejected403() {
        AuthFilter filter = filter(token -> claims(Channel.C));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/x").header("Authorization", "tok"));
        assertThatThrownBy(() -> filter.filter(exchange, pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> {
                    GatewayRejectedException rejected = (GatewayRejectedException) ex;
                    assertThat(rejected.httpStatus()).isEqualTo(403);
                    assertThat(rejected.errorCode()).isEqualTo(GatewayError.CHANNEL_MISMATCH);
                });
    }

    @Test
    void missingChannelClaimRejected403() {
        AuthFilter filter = filter(token -> new UserTokenClaims("u-1", "t-1", null, Set.of(), "access", "jti"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/x").header("Authorization", "tok"));
        assertThatThrownBy(() -> filter.filter(exchange, pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> assertThat(((GatewayRejectedException) ex).errorCode())
                        .isEqualTo(GatewayError.CHANNEL_MISMATCH));
    }

    @Test
    void refreshTokenRejected401() {
        AuthFilter filter = filter(token -> new UserTokenClaims("u-1", "t-1", Channel.B, Set.of(), "refresh", "jti"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/x").header("Authorization", "tok"));
        assertThatThrownBy(() -> filter.filter(exchange, pass()).block())
                .isInstanceOf(GatewayRejectedException.class)
                .satisfies(ex -> {
                    GatewayRejectedException rejected = (GatewayRejectedException) ex;
                    assertThat(rejected.httpStatus()).isEqualTo(401);
                    assertThat(rejected.errorCode()).isEqualTo(GatewayError.AUTH_FAILED);
                });
    }

    @Test
    void publicPathSkipsVerification() {
        auth.getB().setPublicPaths(List.of("/admin/sample/test-token"));
        AuthFilter filter = filter(token -> {
            throw new IllegalStateException("不应验签");
        });
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/sample/test-token"));
        filter.filter(exchange, pass()).block();
        assertThat((Object) exchange.getAttribute(ExchangeAttrs.USER_CLAIMS)).isNull();
    }

    @Test
    void nonDualChannelPathSkipsAuth() {
        AuthFilter filter = filter(token -> {
            throw new IllegalStateException("不应验签");
        });
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        filter.filter(exchange, pass()).block();
    }

    @Test
    void bPolicyDisabledSkipsAuth() {
        auth.getB().setEnabled(false);
        AuthFilter filter = filter(token -> {
            throw new IllegalStateException("不应验签");
        });
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
        filter.filter(exchange, pass()).block();
    }
}
