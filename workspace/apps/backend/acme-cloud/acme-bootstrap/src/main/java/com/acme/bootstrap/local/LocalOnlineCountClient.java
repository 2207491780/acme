package com.acme.bootstrap.local;

import com.acme.platform.auth.api.OnlineCountClient;
import com.acme.platform.auth.application.OnlineSessionUseCase;
import com.acme.platform.system.api.ContractResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 单体本地短路适配器（REQ-20261119-011524 确认②③）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 auth 域 {@link OnlineSessionUseCase#countActive()}，行为等价
 * InternalStatsController 的 /internal/v1/online-count 端点。
 * 上下文口径：透明透传调用方 UserContext——消费方 system 侧 DashboardUseCase 已按
 * 服务身份（platform-system）绑定（跨域调用包上下文口径单源），本适配器不重绑。
 */
@Component
@Primary
public class LocalOnlineCountClient implements OnlineCountClient {

    private final OnlineSessionUseCase onlineSessionUseCase;

    public LocalOnlineCountClient(OnlineSessionUseCase onlineSessionUseCase) {
        this.onlineSessionUseCase = onlineSessionUseCase;
    }

    /** 当前活跃会话数（ACTIVE 且未过期；全平台口径不过滤租户，Q-D04）。 */
    @Override
    public ContractResult<Long> onlineCount() {
        return LocalContractResults.ok(onlineSessionUseCase.countActive());
    }
}
