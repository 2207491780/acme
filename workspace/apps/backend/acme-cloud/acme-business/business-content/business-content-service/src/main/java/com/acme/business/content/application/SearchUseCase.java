package com.acme.business.content.application;

import com.acme.business.content.ContentError;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.domain.BizHotSearchWord;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.business.content.infrastructure.repository.BizHotSearchWordRepository;
import com.acme.common.core.BizException;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.mybatisflex.core.query.QueryMethods;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索用例（FR-004；架构设计 2.1）：搜索结果（标题/摘要匹配）与搜索联想（QD-03 独立轻量候选 ≤10）。
 * keyword trim 后须非空（BR-012）；服务端兜底校验 business-content-02-0001；仅 published（BR-002）。
 */
@Service
public class SearchUseCase {

    /** 联想候选上限（QD-03：≤10）。 */
    static final int SUGGESTION_LIMIT = 10;

    private final BizContentRepository repository;
    private final BizHotSearchWordRepository hotSearchWordRepository;

    public SearchUseCase(BizContentRepository repository, BizHotSearchWordRepository hotSearchWordRepository) {
        this.repository = repository;
        this.hotSearchWordRepository = hotSearchWordRepository;
    }

    /** 热搜榜（FR-002）：词表种子数据源（Q-06），服务端顺序 sort_order 升序（BR-014 前端不重排）。 */
    public List<String> hotSearches() {
        return hotSearchWordRepository.findAllByOrderBySortOrderAsc().stream()
                .map(BizHotSearchWord::getKeyword)
                .toList();
    }

    /** 搜索结果（FR-004）：标题/摘要匹配（不区分大小写），分类可选，分页（QD-10）。 */
    public PageResult<BizContent> search(String keyword, String categoryCode, PageQuery query) {
        String kw = requireKeyword(keyword);
        String category = categoryCode == null || categoryCode.trim().isEmpty() ? null : categoryCode.trim();
        String pattern = LikePatterns.contains(kw);
        QueryWrapper wrapper = QueryWrapper.create()
                .where(BizContent::getStatus).eq(ContentStatus.published)
                // PG LIKE 默认反斜杠转义（LikePatterns 含 % 转义）；OR 组作为单一条件树保持优先级
                .and(QueryMethods.lower(BizContent::getTitle).like(pattern)
                        .or(QueryMethods.lower(BizContent::getExcerpt).like(pattern)));
        if (category != null) {
            wrapper.and(BizContent::getCategoryCode).eq(category);
        }
        wrapper.orderBy(BizContent::getUpdatedAt, false).orderBy(BizContent::getId, true);
        return repository.findAll(wrapper, query.page(), query.size());
    }

    /** 搜索联想（FR-004 联想、QD-03）：标题匹配候选 ≤10。 */
    public List<BizContent> suggestions(String keyword) {
        String kw = requireKeyword(keyword);
        // 与 search 同口径转义通配符（BR-005/BR-012 按字面匹配，防通配注入）
        return repository.findAll(QueryWrapper.create()
                .where(QueryMethods.lower(BizContent::getTitle).like(LikePatterns.contains(kw)))
                .orderBy(BizContent::getUpdatedAt, false)
                .orderBy(BizContent::getId, true)
                .limit(SUGGESTION_LIMIT));
    }

    /** BR-012 服务端兜底：trim 后空关键词拒绝（business-content-02-0001）。 */
    private static String requireKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new BizException(ContentError.SEARCH_KEYWORD_EMPTY);
        }
        return keyword.trim();
    }
}
