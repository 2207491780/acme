package com.acme.platform.infra.infrastructure.repository;

import com.acme.platform.infra.domain.OpAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 审计事件仓库（@TenantId 自动过滤；事件行不可变，仅插入与查询）。 */
public interface OpAuditEventRepository
        extends JpaRepository<OpAuditEvent, Long>, JpaSpecificationExecutor<OpAuditEvent> {

    boolean existsByEventId(String eventId);
}
