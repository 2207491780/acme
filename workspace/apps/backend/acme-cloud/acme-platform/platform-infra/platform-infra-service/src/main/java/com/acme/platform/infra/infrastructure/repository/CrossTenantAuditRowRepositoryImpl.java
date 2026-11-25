package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.infra.domain.CrossTenantAuditRow;
import com.acme.platform.infra.infrastructure.mapper.CrossTenantAuditRowMapper;
import org.springframework.stereotype.Repository;

/** 跨租户审计落库仓库实现（MyBatis-Flex 底座；仅插入面）。 */
@Repository
public class CrossTenantAuditRowRepositoryImpl extends FlexRepositorySupport<CrossTenantAuditRow>
        implements CrossTenantAuditRowRepository {

    public CrossTenantAuditRowRepositoryImpl(CrossTenantAuditRowMapper mapper) {
        super(mapper);
    }
}
