package com.acme.framework.gateway.interceptor;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.framework.gateway.internal.GatewayRejectedException;
import com.acme.framework.gateway.support.GatewayError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关自身错误封装测试（FR-025、8.8、Q-D11、AC-019）。
 */
class GatewayErrorWebExceptionHandlerTest {

    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler();

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/admin/x"));
    }

    @Test
    void authFailureMaps401() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new BizException(GatewayError.AUTH_FAILED)).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.valueOf(401));
        assertThat(exchange.getResponse().getBodyAsString().block()).contains(GatewayError.AUTH_FAILED.code());
    }

    @Test
    void crossChannelMaps403() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new GatewayRejectedException(GatewayError.CHANNEL_MISMATCH)).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.valueOf(403));
    }

    @Test
    void rateLimitedMaps429() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new GatewayRejectedException(GatewayError.RATE_LIMITED)).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.valueOf(429));
    }

    @Test
    void timeoutMaps504RouteFailed() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new RuntimeException(new TimeoutException("read timed out"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.valueOf(504));
        assertThat(exchange.getResponse().getBodyAsString().block()).contains(GatewayError.ROUTE_FAILED.code());
    }

    @Test
    void unknownErrorMapsMinimal500() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new IllegalStateException("internal-secret")).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.valueOf(500));
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains(CommonError.INTERNAL_ERROR.code());
        assertThat(body).doesNotContain("internal-secret");
    }
}
