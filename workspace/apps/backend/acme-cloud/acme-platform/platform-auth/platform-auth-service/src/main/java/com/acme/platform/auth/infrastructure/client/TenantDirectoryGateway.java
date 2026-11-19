package com.acme.platform.auth.infrastructure.client;

import com.acme.framework.core.support.ServiceIdentityScope;
import com.acme.platform.system.api.TenantClient;
import com.acme.platform.system.api.TenantInternalView;
import com.acme.platform.system.api.TenantSummary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 租户目录网关（login-tenant-select / REQ-20261117-025200）：登录页租户下拉数据源
 * 与租户 by-code 校验数据源（登录过期校验 / 切换目标校验共用契约，确认记录③④），
 * 以服务身份主体调用（Q-D08，同 AuthProfileGateway 口径）；下游错误透传（可观测不掩盖故障）。
 */
@Component
public class TenantDirectoryGateway {

    private final TenantClient tenantClient;

    public TenantDirectoryGateway(TenantClient tenantClient) {
        this.tenantClient = tenantClient;
    }

    /** 启用租户清单（按租户代码排序；空租户域由下游返回空列表）。 */
    public List<TenantSummary> listEnabled() {
        List<TenantSummary> tenants = ServiceIdentityScope
                .runAs(AuthProfileGateway.SERVICE_IDENTITY, () -> tenantClient.listEnabled().payload());
        return tenants == null ? List.of() : tenants;
    }

    /**
     * 按租户代码查租户详情（REQ-20261117-025200，确认记录③④；服务身份主体调用）。
     * 未知租户由下游返回 404 错误信封（TENANT_NOT_FOUND），经 framework-feign 错误解码
     * 抛 BizException 透传调用方（登录链统一映射 LOGIN_FAILED / 切换链统一映射 TENANT_SWITCH_FAILED）。
     */
    public TenantInternalView findByCode(String tenantCode) {
        return ServiceIdentityScope.runAs(AuthProfileGateway.SERVICE_IDENTITY,
                () -> tenantClient.findByCode(tenantCode).payload());
    }
}
