package com.acme.platform.system.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.system.api.TenantSummary;
import com.acme.platform.system.application.TenantUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户目录内部接口（login-tenant-select；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1），供 platform-auth 登录页租户下拉聚合。
 */
@RestController
public class InternalTenantController {

    private final TenantUseCase tenantUseCase;

    public InternalTenantController(TenantUseCase tenantUseCase) {
        this.tenantUseCase = tenantUseCase;
    }

    /** 启用租户清单（登录下拉数据源；平台级跨租户查询由用例 @IgnoreTenant 声明）。 */
    @GetMapping("/internal/v1/tenants")
    public Result<List<TenantSummary>> listEnabled() {
        return Result.ok(tenantUseCase.listEnabled().stream()
                .map(t -> new TenantSummary(t.getTenantCode(), t.getTenantName()))
                .toList());
    }
}
