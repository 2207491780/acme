package com.acme.platform.infra.infrastructure.mapper;

import com.acme.platform.infra.domain.OpAuditEvent;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 审计事件 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface OpAuditEventMapper extends BaseMapper<OpAuditEvent> {
}
