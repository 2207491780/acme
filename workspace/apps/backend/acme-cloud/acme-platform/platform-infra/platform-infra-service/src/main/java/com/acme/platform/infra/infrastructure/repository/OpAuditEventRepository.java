package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.domain.OpAuditEvent;

/** 审计事件仓库（租户列自动过滤；事件行不可变，仅插入与查询）。 */
public interface OpAuditEventRepository extends FlexRepository<OpAuditEvent> {

    boolean existsByEventId(String eventId);
}
