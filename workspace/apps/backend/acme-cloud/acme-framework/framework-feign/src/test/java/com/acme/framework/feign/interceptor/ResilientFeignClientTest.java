package com.acme.framework.feign.interceptor;

import com.acme.common.core.BizException;
import com.acme.framework.feign.config.FeignProperties;
import com.acme.framework.feign.internal.IdempotentMarkers;
import com.acme.framework.feign.support.FeignError;
import feign.Client;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 超时/幂等重试/熔断（FR-018/019/020、AC-019/020、10.3.3/10.4）。
 */
class ResilientFeignClientTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final FeignProperties properties = new FeignProperties();

    @AfterEach
    void cleanup() {
        IdempotentMarkers.clear();
    }

    private static Request request(Request.HttpMethod method) {
        RequestTemplate template = new RequestTemplate();
        template.method(method);
        template.uri("/echo");
        feign.RequestTemplate resolved = template.resolve(java.util.Collections.emptyMap());
        return resolved.request();
    }

    private static Response response(int status, Request.HttpMethod method) {
        return Response.builder()
                .status(status)
                .reason(status >= 400 ? "Error" : "OK")
                .headers(headers())
                .body("{\"ok\":true}", StandardCharsets.UTF_8)
                .request(request(method))
                .build();
    }

    private static Response ok() {
        return response(200, Request.HttpMethod.GET);
    }

    private static java.util.Map<String, java.util.Collection<String>> headers() {
        return java.util.Collections.emptyMap();
    }

    private Client failingThenOk(IOException firstFailure) {
        return (req, options) -> {
            if (calls.incrementAndGet() == 1) {
                throw firstFailure;
            }
            return ok();
        };
    }

    private ResilientFeignClient client(Client delegate) {
        properties.getRetry().setMaxAttempts(2);
        properties.getRetry().setBackoffInitialMillis(1);
        return new ResilientFeignClient(delegate, properties, CircuitBreakerRegistry.ofDefaults());
    }

    // ---------- AC-019 幂等重试 ----------

    @Test
    void idempotentGetRetriesOnIoFailure() throws Exception {
        ResilientFeignClient client = client(failingThenOk(new IOException("boom")));
        Response response = client.execute(request(Request.HttpMethod.GET), new Request.Options());
        assertThat(response.status()).isEqualTo(200);
        assertThat(calls).as("幂等调用重试（FR-019）").hasValue(2);
    }

    @Test
    void nonIdempotentPostDoesNotRetry() {
        ResilientFeignClient client = client(failingThenOk(new IOException("boom")));
        assertThatThrownBy(() -> client.execute(request(Request.HttpMethod.POST), new Request.Options()))
                .isInstanceOf(IOException.class);
        assertThat(calls).as("非幂等不重试（FR-019）").hasValue(1);
    }

    @Test
    void feignIdempotentMarkerOverridesMethodDefault() throws Exception {
        IdempotentMarkers.mark(true);
        ResilientFeignClient client = client(failingThenOk(new IOException("boom")));
        Response response = client.execute(request(Request.HttpMethod.POST), new Request.Options());
        assertThat(response.status()).isEqualTo(200);
        assertThat(calls).as("@FeignIdempotent 覆盖（取舍 #9）").hasValue(2);
    }

    @Test
    void nonIdempotentMarkerDisablesRetryForGet() {
        IdempotentMarkers.mark(false);
        ResilientFeignClient client = client(failingThenOk(new IOException("boom")));
        assertThatThrownBy(() -> client.execute(request(Request.HttpMethod.GET), new Request.Options()))
                .isInstanceOf(IOException.class);
        assertThat(calls).hasValue(1);
    }

    // ---------- AC-020 熔断 ----------

    @Test
    void openCircuitFailsFastWithoutTouchingDownstream() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = registry.circuitBreaker("default");
        breaker.transitionToOpenState();
        AtomicInteger touched = new AtomicInteger();
        ResilientFeignClient client = new ResilientFeignClient(
                (req, options) -> {
                    touched.incrementAndGet();
                    return ok();
                }, properties, registry);
        assertThatThrownBy(() -> client.execute(request(Request.HttpMethod.GET), new Request.Options()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(FeignError.CIRCUIT_OPEN));
        assertThat(touched).as("熔断期不击穿下游（FR-020）").hasValue(0);
    }

    @Test
    void failuresRecordedTowardCircuitBreaker() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        properties.getRetry().setMaxAttempts(1);
        ResilientFeignClient client = new ResilientFeignClient(
                (req, options) -> {
                    throw new IOException("down");
                }, properties, registry);
        for (int i = 0; i < 5; i++) {
            try {
                client.execute(request(Request.HttpMethod.GET), new Request.Options());
            } catch (IOException ignored) {
                // 记录失败
            }
        }
        assertThat(registry.circuitBreaker("default").getMetrics().getNumberOfFailedCalls())
                .as("失败计入熔断指标（FR-020）").isGreaterThan(0);
    }

    @Test
    void serverErrorCountsAsFailureForBreaker() throws Exception {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        properties.getRetry().setMaxAttempts(1);
        ResilientFeignClient client = new ResilientFeignClient(
                (req, options) -> response(503, Request.HttpMethod.GET), properties, registry);
        client.execute(request(Request.HttpMethod.GET), new Request.Options());
        assertThat(registry.circuitBreaker("default").getMetrics().getNumberOfFailedCalls())
                .as("5xx 计失败").isEqualTo(1);
    }

    @Test
    void clientErrorCountsAsSuccessForBreaker() throws Exception {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        properties.getRetry().setMaxAttempts(1);
        ResilientFeignClient client = new ResilientFeignClient(
                (req, options) -> response(404, Request.HttpMethod.GET), properties, registry);
        client.execute(request(Request.HttpMethod.GET), new Request.Options());
        assertThat(registry.circuitBreaker("default").getMetrics().getNumberOfFailedCalls())
                .as("4xx 计成功").isZero();
        assertThat(registry.circuitBreaker("default").getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void ignoredStatusCodesDoNotCountAsFailure() throws Exception {
        properties.getCircuit().getIgnoreStatusCodes().add(503);
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        properties.getRetry().setMaxAttempts(1);
        ResilientFeignClient client = new ResilientFeignClient(
                (req, options) -> response(503, Request.HttpMethod.GET), properties, registry);
        client.execute(request(Request.HttpMethod.GET), new Request.Options());
        assertThat(registry.circuitBreaker("default").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void circuitDisabledShortCircuitsBreaker() throws Exception {
        properties.getCircuit().setEnabled(false);
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = registry.circuitBreaker("default");
        breaker.transitionToOpenState();
        AtomicInteger touched = new AtomicInteger();
        ResilientFeignClient client = new ResilientFeignClient(
                (req, options) -> {
                    touched.incrementAndGet();
                    return ok();
                }, properties, registry);
        assertThat(client.execute(request(Request.HttpMethod.GET), new Request.Options()).status()).isEqualTo(200);
        assertThat(touched).as("熔断关闭时直连 delegate（不查熔断态）").hasValue(1);
    }

    // ---------- 幂等判定（取舍 #9） / 超时覆盖（10.4） ----------

    @Test
    void idempotencyDefaultsByHttpMethod() {
        assertThat(ResilientFeignClient.isIdempotent(request(Request.HttpMethod.GET))).isTrue();
        assertThat(ResilientFeignClient.isIdempotent(request(Request.HttpMethod.DELETE))).isTrue();
        assertThat(ResilientFeignClient.isIdempotent(request(Request.HttpMethod.POST))).isFalse();
    }

    @Test
    void clientNameFallsBackToDefault() {
        assertThat(ResilientFeignClient.clientName(request(Request.HttpMethod.GET))).isEqualTo("default");
    }

    @Test
    void perClientTimeoutOverrideApplies() {
        FeignProperties.ClientOverride override = new FeignProperties.ClientOverride();
        override.setConnectTimeoutMillis(111L);
        override.setReadTimeoutMillis(222L);
        properties.getClients().put("order-service", override);
        Request.Options resolved = properties.resolveOptions("order-service", new Request.Options(3000, 5000));
        assertThat(resolved.connectTimeoutMillis()).isEqualTo(111);
        assertThat(resolved.readTimeoutMillis()).isEqualTo(222);
        assertThat(properties.resolveOptions("other", new Request.Options(3000, 5000)).readTimeoutMillis())
                .isEqualTo(5000);
    }
}
