package com.acme.framework.feign.internal;

import com.acme.common.core.BizException;
import com.acme.framework.feign.support.FeignError;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 传输异常映射（FR-018/021：超时/失败转 BizException）。
 */
class FeignErrorsTest {

    @Test
    void timeoutMapsToTimeoutError() {
        RuntimeException mapped = FeignErrors.map(new SocketTimeoutException("read timed out"));
        assertThat(mapped).isInstanceOf(BizException.class);
        assertThat(((BizException) mapped).errorCode()).isEqualTo(FeignError.TIMEOUT);
    }

    @Test
    void nestedTimeoutCauseMapped() {
        RuntimeException mapped = FeignErrors.map(
                new IllegalStateException("wrapper", new SocketTimeoutException("timed out")));
        assertThat(((BizException) mapped).errorCode()).isEqualTo(FeignError.TIMEOUT);
    }

    @Test
    void otherFailuresMapToCallFailed() {
        RuntimeException mapped = FeignErrors.map(new IllegalStateException("boom"));
        assertThat(((BizException) mapped).errorCode()).isEqualTo(FeignError.CALL_FAILED);
    }

    @Test
    void bizExceptionPassesThrough() {
        BizException original = new BizException(FeignError.CIRCUIT_OPEN);
        assertThat(FeignErrors.map(original)).isSameAs(original);
    }
}
