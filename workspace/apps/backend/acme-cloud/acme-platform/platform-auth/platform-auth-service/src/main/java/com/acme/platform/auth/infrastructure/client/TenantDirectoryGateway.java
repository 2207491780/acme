package com.acme.platform.auth.infrastructure.client;

import com.acme.common.context.UserContext;
import com.acme.platform.system.api.TenantClient;
import com.acme.platform.system.api.TenantSummary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 租户目录网关（login-tenant-select）：登录页租户下拉数据源，
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
        UserContext previous = UserContext.get();
        UserContext.bind(new UserContext(AuthProfileGateway.SERVICE_IDENTITY, Set.of()));
        try {
            List<TenantSummary> tenants = tenantClient.listEnabled().payload();
            return tenants == null ? List.of() : tenants;
        } finally {
            UserContext.bind(previous);
        }
    }
}
