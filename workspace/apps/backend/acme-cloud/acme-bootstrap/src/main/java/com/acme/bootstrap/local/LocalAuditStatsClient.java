package com.acme.bootstrap.local;

import com.acme.platform.infra.api.ActionCount;
import com.acme.platform.infra.api.AuditStatsClient;
import com.acme.platform.infra.application.AuditStatsUseCase;
import com.acme.platform.system.api.ContractResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单体本地短路适配器（REQ-20261119-011524 确认②③）：单体本地短路；分布式由 Feign 提供
 * （{@code @Primary} + Feign 惰性化解，同 LocalAuthProfileClient 类注）。
 * 直调 infra 域 {@link AuditStatsUseCase#actionDistribution(int, int)}，行为等价
 * InternalAuditStatsController 的 /internal/v1/audit-stats/distribution 端点。
 * 上下文口径：透明透传调用方 UserContext——消费方 system 侧 DashboardUseCase 已按
 * 服务身份（platform-system）绑定；跨租户平台口径由用例 {@code @IgnoreTenant} 声明
 * （旁路切面经 Spring 代理触发，与分布式 HTTP 路径同语义）。
 */
@Component
@Primary
public class LocalAuditStatsClient implements AuditStatsClient {

    private final AuditStatsUseCase auditStatsUseCase;

    public LocalAuditStatsClient(AuditStatsUseCase auditStatsUseCase) {
        this.auditStatsUseCase = auditStatsUseCase;
    }

    /** 近 N 日审计动作分布 top M（按条数降序；无事件返回空列表——与端点实现同径）。 */
    @Override
    public ContractResult<List<ActionCount>> actionDistribution(int days, int top) {
        return LocalContractResults.ok(auditStatsUseCase.actionDistribution(days, top));
    }
}
