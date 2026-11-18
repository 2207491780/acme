package com.acme.framework.tenant.internal;

import com.acme.common.context.TenantContext;
import com.acme.framework.tenant.support.TenantBypassScope;
import com.mybatisflex.core.tenant.TenantFactory;

/**
 * MyBatis-Flex 租户标识工厂（FR-001/002、AC-001）：唯一取值来源 {@link TenantContext}（只读消费，
 * 写入源口径见 Modules.md 2.4.4/3.1.2）。
 *
 * <p>缺租户 fail-closed（platform-services-bootstrap 修订口径）：拒绝下沉到数据操作面——返回
 * {@link #UNBOUND_TENANT} 未绑定哨兵（读恒空集，过滤不匹配任何真实租户；写入被
 * {@code TenantWriteGuard} 显式拒绝 TENANT_REQUIRED，不产生哨兵租户脏行）。</p>
 *
 * <p>旁路（{@link TenantBypassScope}，架构设计 5.4）：返回 null（官方语义：忽略租户条件，
 * 读全租户、写入保留实体自设租户值）。</p>
 *
 * <p>新增值注入由 MyBatis-Flex 内建：insert 时 TenantFactory 返回值自动覆盖实体租户列
 * （返回 null/空数组时保留实体自设值），无需手动注入。internal 包，模块外不得引用（Modules.md 2.3）。</p>
 */
public class FlexTenantFactory implements TenantFactory {

    /** 未绑定哨兵（缺租户上下文的数据域标识；不得与真实租户 id 冲突）。 */
    public static final String UNBOUND_TENANT = "__acme_tenant_unbound__";

    @Override
    public Object[] getTenantIds() {
        if (TenantBypassScope.isActive()) {
            // 旁路：忽略租户条件（官方语义：读全租户、新增保留实体自设值）
            return null;
        }
        TenantContext context = TenantContext.get();
        String tenantId = context == null ? null : context.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // 缺租户不静默放行（fail-closed 保留）：返回未绑定哨兵，读恒空集、写被 TenantWriteGuard 拒绝
            return new Object[]{UNBOUND_TENANT};
        }
        return new Object[]{tenantId.trim()};
    }
}
