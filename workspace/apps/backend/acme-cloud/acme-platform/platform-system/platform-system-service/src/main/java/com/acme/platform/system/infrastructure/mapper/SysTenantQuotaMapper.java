package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysTenantQuota;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 租户配额覆盖 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysTenantQuotaMapper extends BaseMapper<SysTenantQuota> {
}
