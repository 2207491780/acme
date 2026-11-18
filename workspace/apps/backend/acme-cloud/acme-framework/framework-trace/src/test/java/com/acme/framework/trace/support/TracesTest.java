package com.acme.framework.trace.support;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成/绑定/清理语义测试（FR-001；DQ-001 生成口径、BR-013 常量、FR-002 清理吞错）。
 */
class TracesTest {

    @AfterEach
    void tearDown() {
        Traces.clear();
    }

    // ---------- 生成（DQ-001） ----------

    @Test
    void newTraceIdIs32Hex() {
        assertThat(Traces.newTraceId()).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void newSpanIdIs16Hex() {
        assertThat(Traces.newSpanId()).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void generatedIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(Traces.newTraceId());
            ids.add(Traces.newSpanId());
        }
        assertThat(ids).hasSize(200);
    }

    // ---------- resolve（FR-002~004、DQ-001 两头独立） ----------

    @Test
    void resolveReusesBothInboundValues() {
        TraceContext ctx = Traces.resolve("a".repeat(32), "b".repeat(16));
        assertThat(ctx.traceId()).isEqualTo("a".repeat(32));
        assertThat(ctx.spanId()).isEqualTo("b".repeat(16));
    }

    @Test
    void resolveGeneratesMissingEndsIndependently() {
        TraceContext ctx = Traces.resolve("a".repeat(32), null);
        assertThat(ctx.traceId()).isEqualTo("a".repeat(32));
        assertThat(ctx.spanId()).hasSize(16).isNotEqualTo("a".repeat(32));

        TraceContext other = Traces.resolve(null, "b".repeat(16));
        assertThat(other.traceId()).hasSize(32);
        assertThat(other.spanId()).isEqualTo("b".repeat(16));
    }

    @Test
    void blankHeadersTreatedAsMissing() {
        TraceContext ctx = Traces.resolve("  ", "");
        assertThat(ctx.traceId()).hasSize(32);
        assertThat(ctx.spanId()).hasSize(16);
    }

    // ---------- 绑定/清理（FR-001、BR-013/BR-014） ----------

    @Test
    void bindSyncsTraceContextAndMdc() {
        TraceContext ctx = new TraceContext("t-1", "s-1");
        Traces.bind(ctx);
        assertThat(Traces.current()).isSameAs(ctx);
        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isEqualTo("t-1");
        assertThat(MDC.get(TraceConstants.MDC_SPAN_ID)).isEqualTo("s-1");
    }

    @Test
    void rebindLastOneWins() {
        Traces.bind(new TraceContext("t-1", "s-1"));
        Traces.bind(new TraceContext("t-2", "s-2"));
        assertThat(Traces.current().traceId()).isEqualTo("t-2");
        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isEqualTo("t-2");
    }

    @Test
    void clearRemovesTraceContextAndMdc() {
        Traces.bind(new TraceContext("t-1", "s-1"));
        Traces.clear();
        assertThat(Traces.current()).isNull();
        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceConstants.MDC_SPAN_ID)).isNull();
    }

    @Test
    void bindNullEqualsClear() {
        Traces.bind(new TraceContext("t-1", "s-1"));
        Traces.bind(null);
        assertThat(Traces.current()).isNull();
        assertThat(MDC.get(TraceConstants.MDC_TRACE_ID)).isNull();
    }
}
