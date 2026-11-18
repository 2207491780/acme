package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysDictItem;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 字典项 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysDictItemMapper extends BaseMapper<SysDictItem> {
}
