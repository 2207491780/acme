package com.acme.business.content.infrastructure.mapper;

import com.acme.business.content.domain.BizHotSearchWord;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 热搜词 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface BizHotSearchWordMapper extends BaseMapper<BizHotSearchWord> {
}
