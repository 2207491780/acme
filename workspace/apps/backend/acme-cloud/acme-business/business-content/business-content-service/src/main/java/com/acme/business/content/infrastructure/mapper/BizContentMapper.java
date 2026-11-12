package com.acme.business.content.infrastructure.mapper;

import com.acme.business.content.domain.BizContent;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 内容 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface BizContentMapper extends BaseMapper<BizContent> {
}
