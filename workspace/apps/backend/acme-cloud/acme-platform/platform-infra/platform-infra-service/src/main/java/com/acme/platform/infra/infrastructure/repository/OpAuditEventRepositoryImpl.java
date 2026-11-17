package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.internal.FlexDeletes;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.mapper.OpAuditEventMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 审计事件仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class OpAuditEventRepositoryImpl extends FlexRepositorySupport<OpAuditEvent>
        implements OpAuditEventRepository {

    public OpAuditEventRepositoryImpl(OpAuditEventMapper mapper) {
        super(mapper);
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return exists(QueryWrapper.create().where(OpAuditEvent::getEventId).eq(eventId));
    }

    @Override
    public List<OpAuditEvent> findSoftDeletedExpiredBefore(Instant cutoff) {
        // 软删行查询走逻辑删除旁路（默认查询面恒过滤 deleted=false，FR-009）
        return FlexDeletes.execWithoutLogicDelete(() -> mapper().selectListByQuery(
                QueryWrapper.create()
                        .where(new QueryColumn("deleted").eq(true))
                        .and(new QueryColumn("updated_at").lt(cutoff))));
    }

    @Override
    public int forceDeleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return forceDeleteByQuery(QueryWrapper.create().where(new QueryColumn("id").in(ids)));
    }
}
