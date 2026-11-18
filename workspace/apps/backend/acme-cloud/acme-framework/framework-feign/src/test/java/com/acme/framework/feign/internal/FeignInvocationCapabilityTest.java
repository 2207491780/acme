package com.acme.framework.feign.internal;

import com.acme.common.core.BizException;
import com.acme.framework.feign.support.FeignError;
import com.acme.framework.feign.support.FeignFallback;
import feign.InvocationHandlerFactory;
import feign.Target;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 降级边界（FR-020）：仅基础设施失败降级；BizException/未知异常透传不掩盖业务错误。
 */
class FeignInvocationCapabilityTest {

    /** 最小 ObjectProvider 假件（只用 getIfAvailable）。 */
    static final class SingleProvider<T> implements ObjectProvider<T> {

        private final T value;

        SingleProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }

    private static final class RecordingFallback implements FeignFallback {

        final AtomicBoolean called = new AtomicBoolean();
        final AtomicReference<Throwable> cause = new AtomicReference<>();

        @Override
        public Object fallback(String client, String method, Object[] args, Throwable ex) {
            called.set(true);
            cause.set(ex);
            return "fallback-result";
        }
    }

    private static Object invoke(FeignFallback fallback, RuntimeException thrown) throws Throwable {
        FeignInvocationCapability capability =
                new FeignInvocationCapability(new SingleProvider<>(fallback));
        Method method = Supplier.class.getMethod("get");
        Target<?> target = new Target.HardCodedTarget<>(Supplier.class, "demo");
        InvocationHandlerFactory.MethodHandler failing = args -> {
            throw thrown;
        };
        InvocationHandlerFactory base = (t, dispatch) ->
                (proxy, m, args) -> dispatch.get(m).invoke(args);
        InvocationHandlerFactory enriched = capability.enrich((InvocationHandlerFactory) base);
        java.lang.reflect.InvocationHandler handler =
                enriched.create(target, Map.of(method, failing));
        return handler.invoke(null, method, new Object[0]);
    }

    @Test
    void bizExceptionDoesNotFallBack() throws Throwable {
        RecordingFallback fallback = new RecordingFallback();
        BizException biz = new BizException(FeignError.CALL_FAILED, "业务失败");
        assertThatThrownBy(() -> invoke(fallback, biz))
                .isInstanceOf(BizException.class);
        assertThat(fallback.called).as("BizException 不走 fallback（FR-020）").isFalse();
    }

    @Test
    void timeoutFallsBack() throws Throwable {
        RecordingFallback fallback = new RecordingFallback();
        Object result = invoke(fallback,
                new RuntimeException(new java.net.SocketTimeoutException("read timed out")));
        assertThat(result).isEqualTo("fallback-result");
        assertThat(fallback.called).isTrue();
        assertThat(((BizException) fallback.cause.get()).errorCode()).isEqualTo(FeignError.TIMEOUT);
    }

    @Test
    void circuitOpenFallsBack() throws Throwable {
        RecordingFallback fallback = new RecordingFallback();
        Object result = invoke(fallback, new BizException(FeignError.CIRCUIT_OPEN, "熔断中"));
        assertThat(result).isEqualTo("fallback-result");
        assertThat(fallback.called).isTrue();
    }

    @Test
    void unknownRuntimeDoesNotFallBack() throws Throwable {
        RecordingFallback fallback = new RecordingFallback();
        assertThatThrownBy(() -> invoke(fallback, new IllegalStateException("unknown")))
                .isInstanceOf(BizException.class);
        assertThat(fallback.called).as("未知异常默认透传不降级").isFalse();
    }

    @Test
    void ioFailureFallsBack() throws Throwable {
        RecordingFallback fallback = new RecordingFallback();
        Object result = invoke(fallback, new RuntimeException(new java.io.IOException("connection reset")));
        assertThat(result).isEqualTo("fallback-result");
        assertThat(fallback.called).isTrue();
    }
}
