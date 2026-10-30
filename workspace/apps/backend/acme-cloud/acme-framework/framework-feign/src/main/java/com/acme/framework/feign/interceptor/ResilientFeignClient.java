package com.acme.framework.feign.interceptor;

import com.acme.common.core.BizException;
import com.acme.framework.feign.config.FeignProperties;
import com.acme.framework.feign.internal.IdempotentMarkers;
import com.acme.framework.feign.support.FeignError;
import feign.Client;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 统一装饰 {@link Client}（FR-018/019/020、Q-D004 取舍 #9：自研装饰层收敛超时/幂等重试/熔断）：
 * 超时经 {@link Request.Options}（全局 + 按 client 覆盖，10.4）；重试仅幂等调用（HTTP 方法默认 + {@code @FeignIdempotent}
 * 覆盖）指数退避+上限（10.3.3）；熔断按服务名（裸 resilience4j），开启期直接失败交由降级（FR-020 不击穿下游）；
 * 5xx 计失败、4xx 计成功（客户端错误不归咎下游可用性）；{@code acme.feign.circuit.enabled=false} 时短路熔断。
 */
public class ResilientFeignClient implements Client {

    private static final Logger log = LoggerFactory.getLogger(ResilientFeignClient.class);

    private final Client delegate;
    private final FeignProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ResilientFeignClient(Client delegate, FeignProperties properties,
                                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.delegate = delegate;
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        Request.Options effective = properties.resolveOptions(clientName(request), options);
        boolean idempotent = isIdempotent(request);
        int maxAttempts = idempotent ? Math.max(properties.getRetry().getMaxAttempts(), 1) : 1;
        CircuitBreaker breaker = properties.getCircuit().isEnabled() ? circuitBreaker(request) : null;
        IOException lastIo = null;
        RuntimeException lastRuntime = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeOnce(breaker, request, effective);
            } catch (IOException ex) {
                lastIo = ex;
            } catch (RuntimeException ex) {
                lastRuntime = ex;
                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    throw ex;
                }
            }
            if (attempt < maxAttempts) {
                backoff(attempt);
            }
        }
        if (lastIo != null) {
            throw lastIo;
        }
        throw lastRuntime == null ? new BizException(FeignError.CALL_FAILED) : lastRuntime;
    }

    private Response executeOnce(CircuitBreaker breaker, Request request, Request.Options options)
            throws IOException {
        if (breaker != null && !breaker.tryAcquirePermission()) {
            // 熔断开启：不击穿下游（FR-020），显式失败交由降级
            throw new BizException(FeignError.CIRCUIT_OPEN, "内部调用熔断中");
        }
        long start = System.nanoTime();
        try {
            Response response = delegate.execute(request, options);
            long elapsed = System.nanoTime() - start;
            if (breaker != null) {
                recordOutcome(breaker, response.status(), elapsed);
            }
            return response;
        } catch (IOException | RuntimeException ex) {
            if (breaker != null) {
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, ex);
            }
            throw ex;
        }
    }

    /** 熔断结果口径：5xx 计失败（下游不可用），4xx 计成功（客户端错误不归咎下游）；忽略列表内状态码计成功。 */
    private void recordOutcome(CircuitBreaker breaker, int status, long elapsedNanos) {
        boolean ignored = properties.getCircuit().getIgnoreStatusCodes().contains(status);
        if (!ignored && status >= 500) {
            breaker.onError(elapsedNanos, TimeUnit.NANOSECONDS,
                    new IllegalStateException("下游返回 " + status));
        } else {
            breaker.onSuccess(elapsedNanos, TimeUnit.NANOSECONDS);
        }
    }

    private CircuitBreaker circuitBreaker(Request request) {
        String name = clientName(request);
        return circuitBreakerRegistry.circuitBreaker(name);
    }

    static String clientName(Request request) {
        if (request.requestTemplate() != null && request.requestTemplate().methodMetadata() != null) {
            String configKey = request.requestTemplate().methodMetadata().configKey();
            if (configKey != null && !configKey.isBlank()) {
                int hash = configKey.indexOf('#');
                return hash > 0 ? configKey.substring(0, hash) : configKey;
            }
        }
        return "default";
    }

    /** 幂等判定（取舍 #9）：@FeignIdempotent 覆盖优先，缺省按 HTTP 方法。 */
    static boolean isIdempotent(Request request) {
        Boolean marker = IdempotentMarkers.current();
        if (marker != null) {
            return marker;
        }
        Request.HttpMethod method = request.httpMethod();
        return method == Request.HttpMethod.GET || method == Request.HttpMethod.HEAD
                || method == Request.HttpMethod.PUT || method == Request.HttpMethod.DELETE
                || method == Request.HttpMethod.OPTIONS;
    }

    /** 可重试：仅传输失败（超时/IO 类）；熔断开启/业务错误不重试（10.3.3）。 */
    private static boolean isRetryable(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void backoff(int attempt) {
        long delay = (long) (properties.getRetry().getBackoffInitialMillis()
                * Math.pow(properties.getRetry().getBackoffMultiplier(), attempt - 1));
        delay = Math.min(delay, properties.getRetry().getBackoffMaxMillis());
        log.debug("幂等调用退避重试（10.3.3）: attempt={} delay={}ms", attempt, delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
