package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysDept;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 部门 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysDeptMapper extends BaseMapper<SysDept> {
}
