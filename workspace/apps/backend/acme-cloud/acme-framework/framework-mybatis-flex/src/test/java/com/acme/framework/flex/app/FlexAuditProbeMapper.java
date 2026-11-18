package com.acme.framework.flex.app;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计探针 mapper（切片测试专用）。
 */
@Mapper
public interface FlexAuditProbeMapper extends BaseMapper<FlexAuditProbe> {
}
