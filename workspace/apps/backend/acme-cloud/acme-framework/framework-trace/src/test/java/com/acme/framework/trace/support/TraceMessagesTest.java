package com.acme.framework.trace.support;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息头注入/提取语义测试（FR-004；DQ-003 出站生成值仅携带不绑定）。
 */
class TraceMessagesTest {

    @AfterEach
    void tearDown() {
        Traces.clear();
    }

    /** 有上下文：注入同值。 */
    @Test
    void applyUsesCurrentContextValues() {
        Traces.bind(new TraceContext("t-1", "s-1"));
        Map<String, Object> headers = new HashMap<>();
        TraceMessages.apply(headers);
        assertThat(headers).containsEntry(TraceConstants.TRACE_ID_HEADER, "t-1")
                .containsEntry(TraceConstants.SPAN_ID_HEADER, "s-1");
    }

    /** 无上下文：生成后仅携带、不绑定调用方线程（DQ-003）。 */
    @Test
    void applyGeneratesWithoutBindingWhenNoContext() {
        Map<String, Object> headers = new HashMap<>();
        TraceMessages.apply(headers);
        assertThat((String) headers.get(TraceConstants.TRACE_ID_HEADER)).hasSize(32);
        assertThat((String) headers.get(TraceConstants.SPAN_ID_HEADER)).hasSize(16);
        assertThat(Traces.current()).isNull();
    }

    /** 消费侧提取同值绑定（FR-004）。 */
    @Test
    void extractBindsSameValues() {
        Map<String, Object> headers = new HashMap<>();
        headers.put(TraceConstants.TRACE_ID_HEADER, "t-2");
        headers.put(TraceConstants.SPAN_ID_HEADER, "s-2");
        try (TraceScope scope = TraceMessages.extract(headers)) {
            assertThat(Traces.current().traceId()).isEqualTo("t-2");
            assertThat(Traces.current().spanId()).isEqualTo("s-2");
        }
        assertThat(Traces.current()).isNull();
    }

    /** 提取失败/缺失按 FR-002 缺失口径生成新值。 */
    @Test
    void extractGeneratesWhenHeadersMissingOrBlank() {
        Map<String, Object> headers = new HashMap<>();
        headers.put(TraceConstants.TRACE_ID_HEADER, "  ");
        try (TraceScope scope = TraceMessages.extract(headers)) {
            assertThat(Traces.current().traceId()).hasSize(32);
            assertThat(Traces.current().spanId()).hasSize(16);
        }
        assertThat(Traces.current()).isNull();
    }

    /** 发送拦截前向后提取可往返（AC-005 双侧口径）。 */
    @Test
    void applyThenExtractRoundTrips() {
        Traces.bind(new TraceContext("t-3", "s-3"));
        Map<String, Object> headers = new HashMap<>();
        TraceMessages.apply(headers);
        Traces.clear();
        try (TraceScope scope = TraceMessages.extract(headers)) {
            assertThat(Traces.current().traceId()).isEqualTo("t-3");
            assertThat(Traces.current().spanId()).isEqualTo("s-3");
        }
    }
}
