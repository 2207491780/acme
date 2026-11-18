package com.acme.bootstrap.local;

import com.acme.platform.auth.api.TenantSessionRevokeClient;
import com.acme.platform.auth.api.TenantSessionRevokeView;
import com.acme.platform.auth.application.TenantSessionRevokeUseCase;
import com.acme.platform.system.api.ContractResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 单体本地短路适配器（REQ-20261118-002130 确认②）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 auth 域 {@link TenantSessionRevokeUseCase}（跨域方向 system → auth），
 * 行为等价 InternalTenantSessionController 的 /internal/v1/tenant-sessions/revocations 端点。
 * 上下文口径：透明透传——调用方 system 侧 {@code TenantSessionRevocationGateway} 已按
 * 服务身份（platform-system）绑定 UserContext（跨域调用包上下文口径单源），本适配器不重绑。
 */
@Component
@Primary
public class LocalTenantSessionRevokeClient implements TenantSessionRevokeClient {

    private final TenantSessionRevokeUseCase tenantSessionRevokeUseCase;

    public LocalTenantSessionRevokeClient(TenantSessionRevokeUseCase tenantSessionRevokeUseCase) {
        this.tenantSessionRevokeUseCase = tenantSessionRevokeUseCase;
    }

    /** 按租户代码批量撤销活跃会话（底账 KICKED + 撤销集写入；未知租户幂等成功返回 0/0）。 */
    @Override
    public ContractResult<TenantSessionRevokeView> revokeByTenant(
            com.acme.platform.auth.api.TenantSessionRevokeRequest request) {
        return LocalContractResults.ok(tenantSessionRevokeUseCase.revokeByTenant(request.tenantCode()));
    }
}
