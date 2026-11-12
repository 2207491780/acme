package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 租户目录查询契约（login-tenant-select）：platform-auth 登录页租户下拉数据源，
 * 以服务身份主体调用（Q-D08，同 AuthProfileClient 口径）；仅返回启用租户。
 */
@FeignClient(name = "platform-system", contextId = "tenantClient")
public interface TenantClient {

    /** 启用租户清单（按租户代码排序；登录下拉与租户校验数据源）。 */
    @GetMapping("/internal/v1/tenants")
    ContractResult<List<TenantSummary>> listEnabled();
}
