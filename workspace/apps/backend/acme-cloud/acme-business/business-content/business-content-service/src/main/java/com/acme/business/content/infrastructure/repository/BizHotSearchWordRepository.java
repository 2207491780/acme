package com.acme.business.content.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.business.content.domain.BizHotSearchWord;

import java.util.List;

/** 热搜词仓库（biz_hot_search_word）。 */
public interface BizHotSearchWordRepository extends FlexRepository<BizHotSearchWord> {

    /** 热搜榜（BR-014 服务端顺序 sort_order 升序，前端不重排）。 */
    List<BizHotSearchWord> findAllByOrderBySortOrderAsc();
}
