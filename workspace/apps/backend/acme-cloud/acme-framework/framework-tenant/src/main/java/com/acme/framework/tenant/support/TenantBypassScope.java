package com.acme.framework.tenant.support;

/**
 * 租户过滤旁路作用域（FR-003/AC-003，架构设计 5.4）：try-with-resources 打开后跳过 {@code @TenantId} 过滤，
 * 仅限平台管理场景（Backend.md 12.4.2 显式声明），入口收敛 {@code @IgnoreTenant} 切面；
 * 机制=Hibernate root tenant 语义（适配点核验选定：{@code CurrentTenantIdentifierResolver.isRoot}
 * 为原生旁路接缝，旁路期间解析为 root 哨兵）。
 *
 * <p>约束（12.4.2）：业务代码不得直接打开旁路；开启即产生结构化审计（含访问点），
 * 必须 try-with-resources 关闭，防止线程池线程残留旁路态（跨租户泄漏）。</p>
 */
public final class TenantBypassScope implements AutoCloseable {

    /** root 租户哨兵（isRoot 判定；不得与真实租户 id 冲突）。 */
    public static final String ROOT_TENANT = "__acme_tenant_root__";

    private static final org.slf4j.Logger AUDIT = org.slf4j.LoggerFactory.getLogger("acme.tenant.audit");

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private TenantBypassScope() {
    }

    /**
     * 打开旁路作用域（可重入；close 恢复进入前状态）。
     *
     * @param accessPoint 访问点标识（审计必填，12.4.2；禁止空值）
     */
    public static TenantBypassScope open(String accessPoint) {
        if (accessPoint == null || accessPoint.isBlank()) {
            throw new IllegalArgumentException("旁路开启必须登记访问点（12.4.2）");
        }
        DEPTH.set(currentDepth() + 1);
        // 旁路触及全租户集合：审计即刻可见（12.4.2 审计完整性）
        AUDIT.info("cross-tenant-audit operator={} tenant=* bypass=ROOT accessPoint={}",
                CrossTenantAuditLogger.operator(), accessPoint);
        return new TenantBypassScope();
    }

    /** 旁路是否激活。 */
    public static boolean isActive() {
        return currentDepth() > 0;
    }

    /** 作用域结束（异常路径同样经 try-with-resources 关闭）。 */
    @Override
    public void close() {
        int depth = currentDepth() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }

    private static int currentDepth() {
        Integer depth = DEPTH.get();
        return depth == null ? 0 : depth;
    }
}
