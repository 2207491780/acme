package com.acme.framework.trace.support;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;

import java.util.Map;

/**
 * 消息 trace 头注入/提取门面（FR-004；传输无关，framework-stream P4 复用）。
 * 出站无上下文时生成值仅随消息携带、不绑定调用方线程（FR-003 异常分支、DQ-003）。
 */
public final class TraceMessages {

    private TraceMessages() {
    }

    /** 发送侧：写入当前或生成的 trace 头（FR-004）。 */
    public static void apply(Map<String, Object> headers) {
        if (headers == null) {
            throw new IllegalArgumentException("headers 不能为空");
        }
        TraceContext current = Traces.current();
        String traceId = current != null ? current.traceId() : Traces.newTraceId();
        String spanId = current != null ? current.spanId() : Traces.newSpanId();
        headers.put(TraceConstants.TRACE_ID_HEADER, traceId);
        headers.put(TraceConstants.SPAN_ID_HEADER, spanId);
    }

    /**
     * 发送侧：仅在缺 trace 头（缺失或空白）时补写当前或生成的值（FR-004），
     * 既有头不覆盖（messageId 幂等语义不被重建破坏）。
     */
    public static void applyIfAbsent(Map<String, Object> headers) {
        if (headers == null) {
            throw new IllegalArgumentException("headers 不能为空");
        }
        TraceContext current = Traces.current();
        if (!hasText(headers.get(TraceConstants.TRACE_ID_HEADER))) {
            headers.put(TraceConstants.TRACE_ID_HEADER, current != null ? current.traceId() : Traces.newTraceId());
        }
        if (!hasText(headers.get(TraceConstants.SPAN_ID_HEADER))) {
            headers.put(TraceConstants.SPAN_ID_HEADER, current != null ? current.spanId() : Traces.newSpanId());
        }
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    /** 消费侧：解析头值（缺失/空白按缺失口径生成新值）并绑定（FR-004）。 */
    public static TraceScope extract(Map<String, Object> headers) {
        if (headers == null) {
            throw new IllegalArgumentException("headers 不能为空");
        }
        return TraceScope.open(headerText(headers, TraceConstants.TRACE_ID_HEADER),
                headerText(headers, TraceConstants.SPAN_ID_HEADER));
    }

    private static String headerText(Map<String, Object> headers, String key) {
        Object value = headers.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
