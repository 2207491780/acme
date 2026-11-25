package com.acme.platform.infra.infrastructure.mapper;

import com.acme.platform.infra.domain.CrossTenantAuditRow;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 跨租户审计落库 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface CrossTenantAuditRowMapper extends BaseMapper<CrossTenantAuditRow> {
}
