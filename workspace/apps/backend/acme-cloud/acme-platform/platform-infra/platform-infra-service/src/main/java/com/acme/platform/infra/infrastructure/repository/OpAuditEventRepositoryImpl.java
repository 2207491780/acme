package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.mapper.OpAuditEventMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

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
}
