package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.api.ActionCount;
import com.acme.platform.infra.domain.OpAuditEvent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 审计事件仓库（租户列自动过滤；事件行不可变，仅插入与查询 + 保留期清理物理旁路）。 */
public interface OpAuditEventRepository extends FlexRepository<OpAuditEvent> {

    boolean existsByEventId(String eventId);

    /**
     * 审计动作分布统计（REQ-20261119-011524 确认记录②，GET /internal/v1/audit-stats/distribution
     * 数据源）：跨租户平台口径（原生聚合 SQL 无自动租户注入，旁路声明与审计由调用方用例
     * {@code @IgnoreTenant} 承担，12.4.2 口径）。
     *
     * @param from 统计窗口下界（含）
     * @param top  截断条数（&lt;1 由实现按 1 处理）
     * @return 按条数降序统计项
     */
    List<ActionCount> actionDistribution(Instant from, int top);

    /**
     * 查询软删且更新时间早于 cutoff 的记录（含已软删行，绕开逻辑删除过滤面；
     * 租户过滤随调用访问点声明——清理任务为平台级跨租户语义）。
     */
    List<OpAuditEvent> findSoftDeletedExpiredBefore(Instant cutoff);

    /** 按主键集合物理删除（软删超期清理闭环，FileRetentionCleanupUseCase 同型）。 */
    int forceDeleteByIds(Collection<Long> ids);
}
