package com.acme.common.context;

import com.acme.common.context.internal.ContextThreadLocals;

import java.util.Set;

/**
 * 用户上下文（FR-007）：身份域——userId、roles。只存值不做解析（BR-007）；值对象不可变（BR-005）。
 * 租户唯一事实源为 {@link TenantContext}（数据域租户），本类不重复持有 tenantId（DP-006 本次定稿）。
 *
 * <p>写入须发生在来源校验通过之后（Modules.md 3.1.2 信任时序）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class UserContext {

    private final String userId;
    private final Set<String> roles;

    public UserContext(String userId, Set<String> roles) {
        this.userId = userId;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public String userId() {
        return userId;
    }

    public Set<String> roles() {
        return roles;
    }

    /** 绑定当前线程（FR-007）；写入源约束见 BR-008。 */
    public static void bind(UserContext value) {
        if (value == null) {
            ContextThreadLocals.USER.remove();
        } else {
            ContextThreadLocals.USER.set(value);
        }
    }

    /**
     * 读取当前线程绑定值（FR-007）；未绑定返回 null（DP-006 本次定稿）。
     */
    public static UserContext get() {
        return ContextThreadLocals.USER.get();
    }

    /** 清理当前线程绑定（FR-007；Modules.md 2.4.3）。 */
    public static void clear() {
        ContextThreadLocals.USER.remove();
    }
}
