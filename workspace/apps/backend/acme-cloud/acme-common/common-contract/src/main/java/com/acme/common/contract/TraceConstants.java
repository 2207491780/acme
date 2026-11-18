package com.acme.common.contract;

/**
 * trace 常量（FR-033；Q-005 结论归 contract；Backend.md 7.1.1）。
 * trace 头沿用 {@code X-Trace-Id}/{@code X-Span-Id} 惯例（Q-011 结论）；MDC key 供 framework-trace 注入/解析引用。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class TraceConstants {

    /** 透传头名。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 透传头名。 */
    public static final String SPAN_ID_HEADER = "X-Span-Id";

    /** MDC key（framework-trace 注入/解析引用）。 */
    public static final String MDC_TRACE_ID = "traceId";

    /** MDC key。 */
    public static final String MDC_SPAN_ID = "spanId";

    private TraceConstants() {
    }
}
