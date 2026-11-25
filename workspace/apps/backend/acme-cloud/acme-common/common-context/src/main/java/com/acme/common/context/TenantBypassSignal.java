package com.acme.common.context;

/**
 * 租户旁路深度信号（FR-003 旁路状态的跨模块共享事实源，REQ-20261125-001736 AC-4）：
 * 五类上下文之外的线程态旁路深度计数。唯一写入口为 framework-tenant
 * {@code TenantBypassScope.open/close}（旁路开启必须经其访问点登记与审计，12.4.2——本类
 * 不提供业务入口）；framework-cache {@code TenantKeyGenerator} 等低层模块只读消费
 * {@link #isActive()}，旁路激活时缓存键取 {@code t:*:} 前缀（旁路结果不与单租户缓存混用）。
 *
 * <p>状态放 common-context（各框架模块公共依赖）保证单源：framework-cache 与 framework-tenant
 * 无相互依赖，深度计数不能两处持有。</p>
 */
public final class TenantBypassSignal {

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private TenantBypassSignal() {
    }

    /** 深度 +1（仅 TenantBypassScope.open 调用）。 */
    public static void push() {
        DEPTH.set(depth() + 1);
    }

    /** 深度 -1（仅 TenantBypassScope.close 调用；归零即移除，防线程池线程残留旁路态）。 */
    public static void pop() {
        int depth = depth() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }

    /** 旁路是否激活（只读消费面）。 */
    public static boolean isActive() {
        return depth() > 0;
    }

    /** 当前深度（可重入计数）。 */
    public static int depth() {
        Integer depth = DEPTH.get();
        return depth == null ? 0 : depth;
    }
}
