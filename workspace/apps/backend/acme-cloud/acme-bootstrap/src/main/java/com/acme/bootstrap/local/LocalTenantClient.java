package com.acme.bootstrap.local;

import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.TenantClient;
import com.acme.platform.system.api.TenantInternalView;
import com.acme.platform.system.api.TenantSummary;
import com.acme.platform.system.application.TenantUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单体本地短路适配器（REQ-20261118-002130 确认②）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 system 域 {@link TenantUseCase}，行为等价于 InternalTenantController 的
 * /internal/v1/tenants 端点（视图组装逐字对齐：TenantSummary 两字段 / TenantInternalView 四字段）。
 * 上下文口径：透明透传调用方 UserContext（auth 登录页/校验链已绑服务身份 platform-auth，
 * 跨租户平台级查询由用例 @IgnoreTenant 声明）。
 */
@Component
@Primary
public class LocalTenantClient implements TenantClient {

    private final TenantUseCase tenantUseCase;

    public LocalTenantClient(TenantUseCase tenantUseCase) {
        this.tenantUseCase = tenantUseCase;
    }

    /** 启用租户清单（按租户代码排序；登录下拉与租户校验数据源）。 */
    @Override
    public ContractResult<List<TenantSummary>> listEnabled() {
        return LocalContractResults.ok(tenantUseCase.listEnabled().stream()
                .map(t -> new TenantSummary(t.getTenantCode(), t.getTenantName()))
                .toList());
    }

    /** 按租户代码查租户详情（未知租户 → 用例显式失败，对齐 TENANT_NOT_FOUND 404 信封语义）。 */
    @Override
    public ContractResult<TenantInternalView> findByCode(String tenantCode) {
        var tenant = tenantUseCase.findByCode(tenantCode);
        return LocalContractResults.ok(new TenantInternalView(tenant.getTenantCode(),
                tenant.getTenantName(), tenant.getStatus().name(), tenant.getExpiresAt()));
    }
}
