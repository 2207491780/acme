package com.acme.platform.infra.application;

import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.platform.infra.api.ActionCount;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 审计动作分布统计用例（REQ-20261119-011524 确认记录②，GET /internal/v1/audit-stats/distribution
 * 数据源）：免权限点内部统计口径（服务身份调用，Q-D08）——聚合面仅动作编码+条数（审计保留域
 * 无敏感内容），无越权面。跨租户平台口径经 {@code @IgnoreTenant} 显式旁路声明 + 审计（12.4.2，
 * AuditRetentionCleanupUseCase 同型；原生聚合 SQL 无自动租户注入，旁路保证口径单源）。
 */
@Service
public class AuditStatsUseCase {

    private final OpAuditEventRepository repository;

    public AuditStatsUseCase(OpAuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 近 N 日审计动作分布 top M（按条数降序；无事件返回空列表）。
     *
     * @param days 统计天数窗口（&lt;1 视为 1，防御非法入参）
     * @param top  截断条数（&lt;1 由仓储按 1 处理）
     * @return 按条数降序统计项
     */
    @IgnoreTenant("infra.audit-stats-distribution")
    public List<ActionCount> actionDistribution(int days, int top) {
        int window = Math.max(days, 1);
        Instant from = LocalDate.now(ZoneId.systemDefault()).minusDays(window - 1L)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        return repository.actionDistribution(from, top);
    }
}
