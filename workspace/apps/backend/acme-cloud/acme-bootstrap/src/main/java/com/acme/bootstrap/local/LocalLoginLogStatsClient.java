package com.acme.bootstrap.local;

import com.acme.platform.auth.api.LoginLogStatsClient;
import com.acme.platform.auth.api.LoginLogStatsPoint;
import com.acme.platform.auth.application.LoginLogQueryUseCase;
import com.acme.platform.system.api.ContractResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单体本地短路适配器（REQ-20261119-011524 确认②③）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 auth 域 {@link LoginLogQueryUseCase#dailyStats(int)}，行为等价
 * InternalStatsController 的 /internal/v1/login-log-stats 端点。
 * 上下文口径：透明透传调用方 UserContext——消费方 system 侧 DashboardUseCase 已按
 * 服务身份（platform-system）绑定（跨域调用包上下文口径单源），本适配器不重绑。
 */
@Component
@Primary
public class LocalLoginLogStatsClient implements LoginLogStatsClient {

    private final LoginLogQueryUseCase loginLogQueryUseCase;

    public LocalLoginLogStatsClient(LoginLogQueryUseCase loginLogQueryUseCase) {
        this.loginLogQueryUseCase = loginLogQueryUseCase;
    }

    /** 近 N 日登录日志按日统计（channel 不分；无日志日不出行——与端点实现同径）。 */
    @Override
    public ContractResult<List<LoginLogStatsPoint>> dailyStats(int days) {
        return LocalContractResults.ok(loginLogQueryUseCase.dailyStats(days));
    }
}
