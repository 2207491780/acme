package com.acme.common.context;

import com.acme.common.context.internal.ContextThreadLocals;

/**
 * 租户上下文（FR-008）：tenantId，租户唯一事实源（数据域租户，DP-006 本次定稿）——
 * 数据过滤、缓存键、消息/调用约定头等一切数据域租户决策一律读本类，不从 {@link UserContext} 取。
 * 只存值不做解析（BR-007）；值对象不可变（BR-005）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class TenantContext {

    private final String tenantId;

    public TenantContext(String tenantId) {
        this.tenantId = tenantId;
    }

    public String tenantId() {
        return tenantId;
    }

    /** 绑定当前线程（FR-008）；写入源约束见 BR-008。 */
    public static void bind(TenantContext value) {
        if (value == null) {
            ContextThreadLocals.TENANT.remove();
        } else {
            ContextThreadLocals.TENANT.set(value);
        }
    }

    /**
     * 读取当前线程绑定值（FR-008）；未绑定返回 null（DP-006 本次定稿）。
     */
    public static TenantContext get() {
        return ContextThreadLocals.TENANT.get();
    }

    /** 清理当前线程绑定（FR-008；Modules.md 2.4.3）。 */
    public static void clear() {
        ContextThreadLocals.TENANT.remove();
    }
}
