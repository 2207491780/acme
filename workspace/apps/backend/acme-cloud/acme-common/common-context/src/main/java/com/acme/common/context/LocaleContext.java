package com.acme.common.context;

import com.acme.common.context.internal.ContextThreadLocals;

import java.util.Locale;

/**
 * 区域设置上下文（FR-010）：Locale。只存值不做解析（BR-007）；Locale 解析与来源不在本模块（Modules.md 2.4.1）。
 *
 * <p>绑定口径（DP-006 定稿，与 {@link UserContext} 一致）：未绑定读取返回 {@code null}；
 * {@code bind(null)} 等价解绑；请求结束必须 {@link #clear()}（Modules.md 2.4.3）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class LocaleContext {

    private final Locale locale;

    public LocaleContext(Locale locale) {
        this.locale = locale;
    }

    public Locale locale() {
        return locale;
    }

    /** 绑定当前线程（FR-010）；{@code null} 即解绑。 */
    public static void bind(LocaleContext value) {
        if (value == null) {
            ContextThreadLocals.LOCALE.remove();
        } else {
            ContextThreadLocals.LOCALE.set(value);
        }
    }

    /** 读取当前线程绑定值（FR-010）；未绑定返回 {@code null}（DP-006 定稿）。 */
    public static LocaleContext get() {
        return ContextThreadLocals.LOCALE.get();
    }

    /** 清理当前线程绑定（FR-010；Modules.md 2.4.3）。 */
    public static void clear() {
        ContextThreadLocals.LOCALE.remove();
    }
}
