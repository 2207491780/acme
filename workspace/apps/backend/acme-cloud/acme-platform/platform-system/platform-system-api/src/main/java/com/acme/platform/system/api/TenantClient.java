package com.acme.platform.system.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 租户目录查询契约（login-tenant-select / REQ-20261117-025200）：platform-auth 登录页租户下拉
 * 数据源与租户 by-code 校验数据源，以服务身份主体调用（Q-D08，同 AuthProfileClient 口径）；
 * 下拉仅返回启用且未过期租户（确认记录③）。
 */
@FeignClient(name = "platform-system", contextId = "tenantClient")
public interface TenantClient {

    /** 启用租户清单（按租户代码排序；登录下拉与租户校验数据源）。 */
    @GetMapping("/internal/v1/tenants")
    ContractResult<List<TenantSummary>> listEnabled();

    /**
     * 按租户代码查租户详情（REQ-20261117-025200：登录过期校验 + 超管切换目标校验共用契约）。
     * 未知租户由服务端返回 404 错误信封（TENANT_NOT_FOUND），不经本类型解码。
     *
     * @param tenantCode 租户代码（全局唯一键）
     * @return 信封携带租户详情；未知租户时响应 404
     */
    @GetMapping("/internal/v1/tenants/by-code")
    ContractResult<TenantInternalView> findByCode(@RequestParam("tenantCode") String tenantCode);
}
