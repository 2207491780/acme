package com.acme.business.content.infrastructure.mapper;

import com.acme.business.content.domain.BizContentCategory;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 内容分类 mapper（starter 按 AutoConfigurationPackages 自动注册 @Mapper）。 */
@Mapper
public interface BizContentCategoryMapper extends BaseMapper<BizContentCategory> {
}
