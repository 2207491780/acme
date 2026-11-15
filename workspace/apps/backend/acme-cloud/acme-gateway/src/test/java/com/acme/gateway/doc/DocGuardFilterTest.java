package com.acme.gateway.doc;

import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合文档关闭守卫测试（REQ-20261114-120106、AC-1）：acme.doc.enabled=false 时 /v3/api-docs/**
 * 前置拒绝 DOC_DISABLED（404 语义）；开启/其他路径放行。
 */
class DocGuardFilterTest {

    private static GatewayDocProperties properties(boolean enabled) {
        GatewayDocProperties properties = new GatewayDocProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    @Test
    void should_rejectApiDocsPathWithDocDisabled_when_switchOff() {
        DocGuardFilter filter = new DocGuardFilter(properties(false));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/v3/api-docs/auth").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty()))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(GatewayRejectedException.class);
                    GatewayRejectedException rejected = (GatewayRejectedException) ex;
                    assertThat(rejected.errorCode()).isEqualTo(GatewayError.DOC_DISABLED);
                    assertThat(rejected.httpStatus()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void should_passThrough_when_switchOn() {
        DocGuardFilter filter = new DocGuardFilter(properties(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/v3/api-docs/auth").build());
        AtomicBoolean chained = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchange, e -> {
                    chained.set(true);
                    return Mono.empty();
                })).verifyComplete();
        assertThat(chained).isTrue();
    }

    @Test
    void should_passThroughOtherPaths_when_switchOff() {
        DocGuardFilter filter = new DocGuardFilter(properties(false));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost/admin/v1/auth/captcha").build());
        AtomicBoolean chained = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchange, e -> {
                    chained.set(true);
                    return Mono.empty();
                })).verifyComplete();
        assertThat(chained).isTrue();
    }
}
