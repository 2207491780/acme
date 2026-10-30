package com.acme.framework.trace.support;

import com.acme.common.context.TraceContext;

/**
 * 作用域绑定（FR-001、BR-014；仿 common-context ContextScope 形态）：
 * 进入绑定 TraceContext + MDC，close 清理；try-with-resources 承载异常/中断路径（BR-014）。
 */
public final class TraceScope implements AutoCloseable {

    private TraceScope() {
    }

    /** 进入绑定（FR-001）。 */
    public static TraceScope open(TraceContext ctx) {
        Traces.bind(ctx);
        return new TraceScope();
    }

    /** 复用或生成后进入绑定（FR-002~004；两头各自独立，DQ-001）。 */
    public static TraceScope open(String rawTraceId, String rawSpanId) {
        return open(Traces.resolve(rawTraceId, rawSpanId));
    }

    /** 清理当前绑定（BR-014）；清理失败吞掉不外抛（FR-002）。 */
    @Override
    public void close() {
        Traces.clear();
    }
}
