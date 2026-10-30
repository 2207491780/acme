package com.acme.framework.tenant.support;

import com.acme.common.context.TenantContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Hibernate 租户标识解析器（FR-001/002、AC-001）：唯一取值来源 {@link TenantContext}（只读消费，
 * 写入源口径见 Modules.md 2.4.4/3.1.2）；旁路作用域解析为 root 哨兵（{@link TenantBypassScope}，isRoot 原生旁路）。
 *
 * <p>缺租户 fail-closed（platform-services-bootstrap 修订，2026-10-22）：拒绝下沉到数据操作面——
 * 会话创建期返回 {@link #UNBOUND_TENANT} 未绑定哨兵（Spring Data 启动期查询派生会开临时会话，
 * 会话级拒绝无法区分元数据校验与数据访问）；哨兵会话下读恒空集（过滤不匹配任何真实租户）、
 * 写被 {@code internal.UnboundTenantGuard} 显式拒绝（TENANT_REQUIRED），fail-closed 语义保留。</p>
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    /** 未绑定哨兵（缺租户上下文的会话标识；不得与真实租户 id 冲突，isRoot=false）。 */
    public static final String UNBOUND_TENANT = "__acme_tenant_unbound__";

    public TenantIdentifierResolver() {
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        if (TenantBypassScope.isActive()) {
            return TenantBypassScope.ROOT_TENANT;
        }
        TenantContext context = TenantContext.get();
        String tenantId = context == null ? null : context.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // 缺租户不静默放行（fail-closed 保留）：返回未绑定哨兵，拒绝下沉到数据操作面（类注释）
            return UNBOUND_TENANT;
        }
        return tenantId.trim();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(String tenantId) {
        return TenantBypassScope.ROOT_TENANT.equals(tenantId);
    }
}
