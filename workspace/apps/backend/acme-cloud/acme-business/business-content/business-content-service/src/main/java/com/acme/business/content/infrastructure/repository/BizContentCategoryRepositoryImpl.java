package com.acme.business.content.infrastructure.repository;

import com.acme.business.content.domain.BizContentCategory;
import com.acme.business.content.infrastructure.mapper.BizContentCategoryMapper;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 内容分类仓库实现（MyBatis-Flex 底座）。 */
@Repository
public class BizContentCategoryRepositoryImpl extends FlexRepositorySupport<BizContentCategory>
        implements BizContentCategoryRepository {

    public BizContentCategoryRepositoryImpl(BizContentCategoryMapper mapper) {
        super(mapper);
    }

    @Override
    public List<BizContentCategory> findAllByOrderBySortOrderAscCodeAsc() {
        return mapper().selectListByQuery(QueryWrapper.create()
                .orderBy(BizContentCategory::getSortOrder, true)
                .orderBy(BizContentCategory::getCode, true));
    }
}
