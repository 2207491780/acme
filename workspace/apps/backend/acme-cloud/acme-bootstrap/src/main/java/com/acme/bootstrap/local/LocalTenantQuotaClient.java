package com.acme.bootstrap.local;

import com.acme.framework.core.support.ServiceIdentityScope;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.TenantQuotaClient;
import com.acme.platform.system.api.TenantQuotaView;
import com.acme.platform.system.application.TenantQuotaUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 单体本地短路适配器（REQ-20261122-150802 确认③）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 system 域 {@link TenantQuotaUseCase#resolveQuota(String)}（{@code @IgnoreTenant} 旁路），
 * 行为等价 InternalTenantController 的 /internal/v1/tenants/quota 端点。
 * 上下文口径：调用方为 infra 文件上传链（无既有网关包装），按 {@code LocalLoginLogCleanupClient}
 * 服务身份先例包上下文（跨域调用绑调用方服务身份 platform-infra，作用域结束恢复）；
 * 分布式形态的调用方透传语义不变。
 */
@Component
@Primary
public class LocalTenantQuotaClient implements TenantQuotaClient {

    /** 调用方服务身份（Q-D08：内部令牌 userId 填服务名，对齐 LocalLoginLogCleanupClient 口径）。 */
    public static final String SERVICE_IDENTITY = "platform-infra";

    private final TenantQuotaUseCase quotaUseCase;

    public LocalTenantQuotaClient(TenantQuotaUseCase quotaUseCase) {
        this.quotaUseCase = quotaUseCase;
    }

    /** 三级覆盖链解析有效配额（未知租户 → 用例显式失败，对齐 TENANT_NOT_FOUND 404 信封语义）。 */
    @Override
    public ContractResult<TenantQuotaView> resolveQuota(String tenantCode) {
        return ServiceIdentityScope.runAs(SERVICE_IDENTITY,
                () -> LocalContractResults.ok(quotaUseCase.resolveQuota(tenantCode)));
    }
}
