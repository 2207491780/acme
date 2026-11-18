package com.acme.platform.auth.interfaces;

import com.acme.common.core.Result;
import com.acme.platform.auth.api.TenantSessionRevokeRequest;
import com.acme.platform.auth.api.TenantSessionRevokeView;
import com.acme.platform.auth.application.TenantSessionRevokeUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户会话批量撤销内部接口（REQ-20261117-025200 确认记录②；不经网关对外，openapi 不登记）：
 * 服务间调用经 framework-security 内部令牌校验（11.6.1），消费方 platform-system
 * 在停用租户事务提交后调用（枚举撤销，失败降级由消费方承担）。
 */
@RestController
public class InternalTenantSessionController {

    private final TenantSessionRevokeUseCase tenantSessionRevokeUseCase;

    public InternalTenantSessionController(TenantSessionRevokeUseCase tenantSessionRevokeUseCase) {
        this.tenantSessionRevokeUseCase = tenantSessionRevokeUseCase;
    }

    /**
     * 按租户代码批量撤销活跃会话（底账 KICKED + 撤销集写入，返回计数；
     * 未知租户无活跃会话幂等成功返回 0/0）。
     */
    @PostMapping("/internal/v1/tenant-sessions/revocations")
    public Result<TenantSessionRevokeView> revokeByTenant(@Valid @RequestBody TenantSessionRevokeRequest request) {
        return Result.ok(tenantSessionRevokeUseCase.revokeByTenant(request.tenantCode()));
    }
}
