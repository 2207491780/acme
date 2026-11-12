package com.acme.business.content.infrastructure.repository;

import com.acme.business.content.domain.BizHotSearchWord;
import com.acme.business.content.infrastructure.mapper.BizHotSearchWordMapper;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 热搜词仓库实现（MyBatis-Flex 底座）。 */
@Repository
public class BizHotSearchWordRepositoryImpl extends FlexRepositorySupport<BizHotSearchWord>
        implements BizHotSearchWordRepository {

    public BizHotSearchWordRepositoryImpl(BizHotSearchWordMapper mapper) {
        super(mapper);
    }

    @Override
    public List<BizHotSearchWord> findAllByOrderBySortOrderAsc() {
        return mapper().selectListByQuery(QueryWrapper.create()
                .orderBy(BizHotSearchWord::getSortOrder, true));
    }
}
