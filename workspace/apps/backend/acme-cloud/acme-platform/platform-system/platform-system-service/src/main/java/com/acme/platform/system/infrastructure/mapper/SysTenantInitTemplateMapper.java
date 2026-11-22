package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysTenantInitTemplate;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 开通模板 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysTenantInitTemplateMapper extends BaseMapper<SysTenantInitTemplate> {
}
