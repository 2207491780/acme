package com.acme.platform.system.infrastructure.client;

import com.acme.framework.core.support.ServiceIdentityScope;
import com.acme.platform.auth.api.TenantSessionRevokeClient;
import com.acme.platform.auth.api.TenantSessionRevokeRequest;
import com.acme.platform.auth.api.TenantSessionRevokeView;
import org.springframework.stereotype.Component;

/**
 * 租户会话批量撤销网关（REQ-20261117-025200 确认记录②）：system 停用租户事务提交后调用，
 * 以服务身份主体调用（Q-D08，同 auth 侧 TenantDirectoryGateway 出站口径）；
 * 下游错误透传（撤销失败降级判定由调用方用例承担——WARN 不回滚停用，确认记录①）。
 */
@Component
public class TenantSessionRevocationGateway {

    /** 服务身份主体标识（Q-D08：内部令牌 userId 填服务名，同 AuthProfileGateway 口径）。 */
    public static final String SERVICE_IDENTITY = "platform-system";

    private final TenantSessionRevokeClient revokeClient;

    public TenantSessionRevocationGateway(TenantSessionRevokeClient revokeClient) {
        this.revokeClient = revokeClient;
    }

    /**
     * 按租户代码批量撤销活跃会话（提供方底账 KICKED + 撤销集写入，返回计数）。
     *
     * @param tenantCode 目标租户代码
     * @return 撤销 / 失败计数（未知租户为 0/0 幂等成功）
     */
    public RevocationOutcome revokeByTenant(String tenantCode) {
        return ServiceIdentityScope.runAs(SERVICE_IDENTITY, () -> {
            TenantSessionRevokeView view = revokeClient
                    .revokeByTenant(new TenantSessionRevokeRequest(tenantCode)).payload();
            return new RevocationOutcome(view.revokedCount(), view.failedCount());
        });
    }

    /** 撤销结果载体（计数口径同 TenantSessionRevokeView）。 */
    public record RevocationOutcome(int revokedCount, int failedCount) {
    }
}
