package com.acme.bootstrap.local;

import com.acme.common.context.UserContext;
import com.acme.platform.auth.application.LoginLogRetentionCleanupUseCase;
import com.acme.platform.infra.internal.client.LoginLogCleanupClient;
import com.acme.platform.system.api.ContractResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 单体本地短路适配器（REQ-20261118-002130 确认②）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 auth 域 {@link LoginLogRetentionCleanupUseCase}（跨域方向 infra → auth，
 * REQ-20261114-120105 TL 裁决 C 方案），行为等价 LoginLogCleanupController 的
 * /internal/v1/login-logs/purge-cleanup 端点。
 * 上下文口径：调用方为 infra 调度任务体（无既有网关包装），按 {@code TenantSessionRevocationGateway}
 * 服务身份先例包上下文（跨域调用绑调用方服务身份 platform-infra，作用域结束恢复）；
 * 分布式形态的调用方透传语义不变。
 */
@Component
@Primary
public class LocalLoginLogCleanupClient implements LoginLogCleanupClient {

    /** 调用方服务身份（Q-D08：内部令牌 userId 填服务名，对齐 TenantSessionRevocationGateway 口径）。 */
    public static final String SERVICE_IDENTITY = "platform-infra";

    private final LoginLogRetentionCleanupUseCase cleanupUseCase;

    public LocalLoginLogCleanupClient(LoginLogRetentionCleanupUseCase cleanupUseCase) {
        this.cleanupUseCase = cleanupUseCase;
    }

    /** 软删标记 + 物理清理（幂等）；data=本次物理清理条数。 */
    @Override
    public ContractResult<Integer> purgeCleanup() {
        UserContext previous = UserContext.get();
        UserContext.bind(new UserContext(SERVICE_IDENTITY, Set.of()));
        try {
            return LocalContractResults.ok(cleanupUseCase.cleanupExpired());
        } finally {
            UserContext.bind(previous);
        }
    }
}
