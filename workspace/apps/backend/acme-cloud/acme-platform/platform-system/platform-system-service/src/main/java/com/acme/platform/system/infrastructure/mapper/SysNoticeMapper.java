package com.acme.platform.system.infrastructure.mapper;

import com.acme.platform.system.domain.SysNotice;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 通知公告 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface SysNoticeMapper extends BaseMapper<SysNotice> {
}
