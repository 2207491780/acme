package com.acme.platform.infra.infrastructure.mapper;

import com.acme.platform.infra.domain.SysFile;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 平台文件 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {
}
