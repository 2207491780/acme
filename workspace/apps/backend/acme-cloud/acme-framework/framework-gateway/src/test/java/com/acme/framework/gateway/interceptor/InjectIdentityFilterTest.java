package com.acme.framework.gateway.interceptor;

import com.acme.common.contract.ServiceHeaders;
import com.acme.common.context.Channel;
import com.acme.framework.gateway.internal.ExchangeAttrs;
import com.acme.framework.gateway.support.UserTokenClaims;
import com.acme.framework.security.support.InternalTokenIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部令牌/身份头注入测试（FR-021、8.4、11.6.1、3.1.2 信任时序闭环）：claims→头映射、伪造头被覆盖、Issuer 缺失跳过。
 */
class InjectIdentityFilterTest {

    private static final class SingleProvider implements ObjectProvider<InternalTokenIssuer> {

        private final InternalTokenIssuer issuer;

        SingleProvider(InternalTokenIssuer issuer) {
            this.issuer = issuer;
        }

        @Override
        public InternalTokenIssuer getObject(Object... args) {
            return issuer;
        }

        @Override
        public InternalTokenIssuer getIfAvailable() {
            return issuer;
        }

        @Override
        public InternalTokenIssuer getIfUnique() {
            return issuer;
        }

        @Override
        public InternalTokenIssuer getObject() {
            return issuer;
        }
    }

    private static UserTokenClaims claims() {
        return new UserTokenClaims("u-1", "t-1", Channel.B, Set.of("admin"), "access", "jti");
    }

    private static GatewayFilterChain capture(AtomicReference<ServerWebExchange> seen, List<String> order) {
        return exchange -> {
            seen.set(exchange);
            order.add("pass");
            return Mono.empty();
        };
    }

    @Test
    void injectsTokenAndIdentityHeadersOverwritingSpoofed() {
        AtomicReference<String> issuedSubject = new AtomicReference<>();
        InternalTokenIssuer issuer = subject -> {
            issuedSubject.set(subject.userId());
            return "internal-jws";
        };
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/x")
                        .header(ServiceHeaders.SERVICE_TOKEN, "spoofed")
                        .header(ServiceHeaders.USER_ID, "spoofed-user"));
        exchange.getAttributes().put(ExchangeAttrs.USER_CLAIMS, claims());
        exchange.getAttributes().put(ExchangeAttrs.DEVICE_ID, "dev-1");

        new InjectIdentityFilter(new SingleProvider(issuer))
                .filter(exchange, capture(seen, new ArrayList<>())).block();

        assertThat(issuedSubject.get()).isEqualTo("u-1");
        var headers = seen.get().getRequest().getHeaders();
        assertThat(headers.getFirst(ServiceHeaders.SERVICE_TOKEN)).isEqualTo("internal-jws");
        assertThat(headers.getFirst(ServiceHeaders.USER_ID)).isEqualTo("u-1");
        assertThat(headers.getFirst(ServiceHeaders.TENANT_ID)).isEqualTo("t-1");
        assertThat(headers.getFirst(ServiceHeaders.CHANNEL)).isEqualTo("B");
        assertThat(headers.getFirst(ServiceHeaders.DEVICE_ID)).isEqualTo("dev-1");
    }

    @Test
    void unauthenticatedPathInjectsNothing() {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/public/x"));
        new InjectIdentityFilter(new SingleProvider(subject -> "internal-jws"))
                .filter(exchange, capture(seen, new ArrayList<>())).block();
        assertThat(seen.get().getRequest().getHeaders().getFirst(ServiceHeaders.SERVICE_TOKEN)).isNull();
    }

    @Test
    void issuerMissingSkipsInjection() {
        // acme.security.enabled=false：注入能力与未引入一致（3.5 开关独立性）
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
        exchange.getAttributes().put(ExchangeAttrs.USER_CLAIMS, claims());
        new InjectIdentityFilter(new SingleProvider(null))
                .filter(exchange, capture(seen, new ArrayList<>())).block();
        assertThat(seen.get().getRequest().getHeaders().getFirst(ServiceHeaders.SERVICE_TOKEN)).isNull();
    }
}
