package com.acme.common.context;

import com.acme.common.context.internal.ContextThreadLocals;

/**
 * 上下文快照（FR-012；BR-009）：capture/restore 覆盖线程池、MQ 消费、定时任务三类跨线程场景；
 * 请求结束必须 clear（Modules.md 2.4.3）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class ContextSnapshot {

    private final UserContext user;
    private final TenantContext tenant;
    private final TraceContext trace;
    private final LocaleContext locale;
    private final ClientContext client;

    private ContextSnapshot(UserContext user, TenantContext tenant, TraceContext trace,
                            LocaleContext locale, ClientContext client) {
        this.user = user;
        this.tenant = tenant;
        this.trace = trace;
        this.locale = locale;
        this.client = client;
    }

    /** 快照当前线程五类 Context（FR-012）。 */
    public static ContextSnapshot capture() {
        return new ContextSnapshot(UserContext.get(), TenantContext.get(), TraceContext.get(),
                LocaleContext.get(), ClientContext.get());
    }

    /**
     * 将快照写回当前线程（FR-012）；先清空再写回，保证与快照一致（重入、部分绑定安全，DP-006 本次定稿）。
     */
    public void restore() {
        ContextThreadLocals.clearAll();
        UserContext.bind(user);
        TenantContext.bind(tenant);
        TraceContext.bind(trace);
        LocaleContext.bind(locale);
        ClientContext.bind(client);
    }

    /** 清空当前线程全部上下文（FR-012；Modules.md 2.4.3 保证请求结束不泄漏 ThreadLocal）。 */
    public static void clear() {
        ContextThreadLocals.clearAll();
    }
}
