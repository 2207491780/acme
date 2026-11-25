package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.domain.CrossTenantAuditRow;

/** 跨租户审计落库仓库（事件行不可变，仅插入；平台级数据无租户过滤面）。 */
public interface CrossTenantAuditRowRepository extends FlexRepository<CrossTenantAuditRow> {
}
