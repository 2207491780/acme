package com.acme.framework.trace.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import com.acme.framework.trace.support.Traces;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 发送侧 trace 拦截（FR-003）：当前有绑定则携带同值；无绑定生成后仅携带、
 * 不绑定调用方线程（FR-003 异常分支、DQ-003）。接收侧复用 HTTP 入口（架构设计 3.4）。
 */
public final class TraceFeignRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        TraceContext current = Traces.current();
        String traceId = current != null ? current.traceId() : Traces.newTraceId();
        String spanId = current != null ? current.spanId() : Traces.newSpanId();
        template.header(TraceConstants.TRACE_ID_HEADER, traceId);
        template.header(TraceConstants.SPAN_ID_HEADER, spanId);
    }
}
