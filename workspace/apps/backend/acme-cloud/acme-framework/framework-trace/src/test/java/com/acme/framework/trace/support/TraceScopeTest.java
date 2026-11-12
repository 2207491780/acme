package com.acme.framework.trace.support;

import com.acme.common.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 作用域绑定测试（FR-001、BR-014：含异常路径仍清理）。
 */
class TraceScopeTest {

    @AfterEach
    void tearDown() {
        Traces.clear();
    }

    @Test
    void openBindsAndCloseClears() {
        try (TraceScope scope = TraceScope.open(new TraceContext("t-1", "s-1"))) {
            assertThat(scope).isNotNull();
            assertThat(MDC.get("traceId")).isEqualTo("t-1");
        }
        assertThat(Traces.current()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void openByRawHeadersResolvesAndBinds() {
        try (TraceScope scope = TraceScope.open("t-2", "s-2")) {
            assertThat(scope).isNotNull();
            assertThat(Traces.current().traceId()).isEqualTo("t-2");
            assertThat(Traces.current().spanId()).isEqualTo("s-2");
        }
        assertThat(Traces.current()).isNull();
    }

    /** 异常退出路径仍完成清理（BR-014、try-with-resources 承载）。 */
    @Test
    void closeStillClearsOnExceptionPath() {
        assertThatCode(() -> {
            try (TraceScope scope = TraceScope.open(new TraceContext("t-3", "s-3"))) {
                throw new IllegalStateException("业务异常");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(Traces.current()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }
}
