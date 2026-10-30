package com.acme.common.context.internal;

import com.acme.common.context.ClientContext;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;

/**
 * 五类 Context 的 ThreadLocal 持有器（Modules.md 2.3、2.4.2：实现细节，模块外不得引用）。
 */
public final class ContextThreadLocals {

    public static final ThreadLocal<UserContext> USER = new ThreadLocal<>();
    public static final ThreadLocal<TenantContext> TENANT = new ThreadLocal<>();
    public static final ThreadLocal<TraceContext> TRACE = new ThreadLocal<>();
    public static final ThreadLocal<LocaleContext> LOCALE = new ThreadLocal<>();
    public static final ThreadLocal<ClientContext> CLIENT = new ThreadLocal<>();

    private ContextThreadLocals() {
    }

    /** 清空当前线程全部上下文（Modules.md 2.4.3）。 */
    public static void clearAll() {
        USER.remove();
        TENANT.remove();
        TRACE.remove();
        LOCALE.remove();
        CLIENT.remove();
    }
}
