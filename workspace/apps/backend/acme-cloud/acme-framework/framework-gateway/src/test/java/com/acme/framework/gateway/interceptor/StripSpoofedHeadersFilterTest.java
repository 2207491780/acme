package com.acme.framework.gateway.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 剥离伪造身份头测试（FR-020、8.3、M-10、AC-016）。
 */
class StripSpoofedHeadersFilterTest {

    private final StripSpoofedHeadersFilter filter = new StripSpoofedHeadersFilter();

    @Test
    void stripsSpoofedIdentityHeadersAndReinjectsSanitizedFacts() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x")
                .header(ServiceHeaders.USER_ID, "fake-user")
                .header(ServiceHeaders.TENANT_ID, "fake-tenant")
                .header(ServiceHeaders.CHANNEL, "C")
                .header(ServiceHeaders.SERVICE_TOKEN, "fake-token")
                .header(ServiceHeaders.DEVICE_ID, "dev-1")
                .header(ServiceHeaders.APP_VERSION, "1.2.3")
                .header("X-Trace-Id", "trace-1"));
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        GatewayFilterChain chain = new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange ex) {
                seen.set(ex);
                return Mono.empty();
            }
        };
        filter.filter(exchange, chain).block();
        ServerWebExchange mutated = seen.get();
        assertThat(mutated.getRequest().getHeaders().getFirst(ServiceHeaders.USER_ID)).isNull();
        assertThat(mutated.getRequest().getHeaders().getFirst(ServiceHeaders.TENANT_ID)).isNull();
        assertThat(mutated.getRequest().getHeaders().getFirst(ServiceHeaders.CHANNEL)).isNull();
        assertThat(mutated.getRequest().getHeaders().getFirst(ServiceHeaders.SERVICE_TOKEN)).isNull();
        assertThat(mutated.getRequest().getHeaders().getFirst(ServiceHeaders.DEVICE_ID)).isEqualTo("dev-1");
        assertThat(mutated.getRequest().getHeaders().getFirst(ServiceHeaders.APP_VERSION)).isEqualTo("1.2.3");
        assertThat(mutated.getRequest().getHeaders().getFirst("X-Trace-Id")).isEqualTo("trace-1");
        assertThat((Object) mutated.getAttribute(ExchangeAttrs.DEVICE_ID)).isEqualTo("dev-1");
    }

    @Test
    void dropsIllegalDeviceId() {
        assertThat(StripSpoofedHeadersFilter.sanitize("bad value!", 64)).isNull();
        assertThat(StripSpoofedHeadersFilter.sanitize("dev_1.ok:2", 64)).isEqualTo("dev_1.ok:2");
        assertThat(StripSpoofedHeadersFilter.sanitize("abcdef", 3)).isEqualTo("abc");
    }

    @Test
    void orderIsFirstInChain() {
        assertThat(filter.getOrder()).isEqualTo(100);
    }
}
