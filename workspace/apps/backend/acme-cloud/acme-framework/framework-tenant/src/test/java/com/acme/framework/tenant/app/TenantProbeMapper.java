package com.acme.framework.tenant.app;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户过滤探针 mapper（切片测试专用）。
 */
@Mapper
public interface TenantProbeMapper extends BaseMapper<TenantProbe> {
}
