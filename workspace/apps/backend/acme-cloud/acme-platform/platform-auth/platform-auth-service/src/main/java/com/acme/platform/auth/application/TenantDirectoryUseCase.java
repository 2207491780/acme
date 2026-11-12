package com.acme.platform.auth.application;

import com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway;
import com.acme.platform.system.api.TenantSummary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 租户目录用例（login-tenant-select）：登录页租户下拉数据源的薄编排
 * （Feign 聚合经 TenantDirectoryGateway，服务身份主体 Q-D08）。
 */
@Service
public class TenantDirectoryUseCase {

    private final TenantDirectoryGateway tenantDirectoryGateway;

    public TenantDirectoryUseCase(TenantDirectoryGateway tenantDirectoryGateway) {
        this.tenantDirectoryGateway = tenantDirectoryGateway;
    }

    /** 启用租户清单（按租户代码排序；下游错误透传，租户域缺失返回空列表）。 */
    public List<TenantSummary> listEnabled() {
        return tenantDirectoryGateway.listEnabled();
    }
}
