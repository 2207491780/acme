package com.acme.framework.tenant.support;

import com.acme.common.context.TenantBypassSignal;

/**
 * 租户过滤旁路作用域（FR-003/AC-003，架构设计 5.4）：try-with-resources 打开后跳过 {@code @TenantId} 过滤，
 * 仅限平台管理场景（Backend.md 12.4.2 显式声明），入口收敛 {@code @IgnoreTenant} 切面；
 * 机制=旁路激活时 {@code FlexTenantFactory.getTenantIds()} 返回 null（MyBatis-Flex 官方语义：
 * 忽略租户条件，读全租户、新增保留实体自设租户值）。
 *
 * <p>约束（12.4.2）：业务代码不得直接打开旁路；开启必须登记访问点（供审计关联），
 * 必须 try-with-resources 关闭，防止线程池线程残留旁路态（跨租户泄漏）。旁路审计单点在
 * {@code CrossTenantAuditLogger.record}（{@code @IgnoreTenant} 切面成功/失败路径均调用，
 * REQ-20261125-001736 AC-2「结构化日志照旧输出」＝每次旁路访问恰一条含 traceId 的审计行；
 * 本类不再直写审计行，避免同一访问双行重复）。</p>
 *
 * <p>深度状态单源（REQ-20261125-001736 AC-4）：旁路深度计数下沉至 common-context
 * {@link TenantBypassSignal}（本类为唯一写入口），framework-cache TenantKeyGenerator 等低层
 * 模块只读消费——无相互依赖的两模块共享同一状态。</p>
 */
public final class TenantBypassScope implements AutoCloseable {

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
        TenantBypassSignal.push();
        return new TenantBypassScope();
    }

    /** 旁路是否激活。 */
    public static boolean isActive() {
        return TenantBypassSignal.isActive();
    }

    /** 作用域结束（异常路径同样经 try-with-resources 关闭）。 */
    @Override
    public void close() {
        TenantBypassSignal.pop();
    }
}
