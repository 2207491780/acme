package com.acme.common.context;

import com.acme.common.context.internal.ContextThreadLocals;

/**
 * 链路追踪上下文（FR-009）：traceId、spanId。只存值不做解析（BR-007）；
 * traceId 生成与 MDC 注入归 framework-trace（Modules.md 3.1）。
 *
 * <p>绑定口径（DP-006 定稿，与 {@link UserContext} 一致）：未绑定读取返回 {@code null}；
 * {@code bind(null)} 等价解绑；请求结束必须 {@link #clear()}（Modules.md 2.4.3）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class TraceContext {

    private final String traceId;
    private final String spanId;

    public TraceContext(String traceId, String spanId) {
        this.traceId = traceId;
        this.spanId = spanId;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    /** 绑定当前线程（FR-009）；{@code null} 即解绑。 */
    public static void bind(TraceContext value) {
        if (value == null) {
            ContextThreadLocals.TRACE.remove();
        } else {
            ContextThreadLocals.TRACE.set(value);
        }
    }

    /** 读取当前线程绑定值（FR-009）；未绑定返回 {@code null}（DP-006 定稿）。 */
    public static TraceContext get() {
        return ContextThreadLocals.TRACE.get();
    }

    /** 清理当前线程绑定（FR-009；Modules.md 2.4.3）。 */
    public static void clear() {
        ContextThreadLocals.TRACE.remove();
    }
}
