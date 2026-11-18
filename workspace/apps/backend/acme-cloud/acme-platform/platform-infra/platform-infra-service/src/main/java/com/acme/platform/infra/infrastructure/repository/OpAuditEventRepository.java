package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.domain.OpAuditEvent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 审计事件仓库（租户列自动过滤；事件行不可变，仅插入与查询 + 保留期清理物理旁路）。 */
public interface OpAuditEventRepository extends FlexRepository<OpAuditEvent> {

    boolean existsByEventId(String eventId);

    /**
     * 查询软删且更新时间早于 cutoff 的记录（含已软删行，绕开逻辑删除过滤面；
     * 租户过滤随调用访问点声明——清理任务为平台级跨租户语义）。
     */
    List<OpAuditEvent> findSoftDeletedExpiredBefore(Instant cutoff);

    /** 按主键集合物理删除（软删超期清理闭环，FileRetentionCleanupUseCase 同型）。 */
    int forceDeleteByIds(Collection<Long> ids);
}
