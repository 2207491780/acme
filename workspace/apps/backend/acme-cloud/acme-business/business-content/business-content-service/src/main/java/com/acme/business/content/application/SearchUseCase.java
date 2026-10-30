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
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        Specification<BizContent> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), ContentStatus.published));
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("excerpt")), pattern, '\\')));
            if (category != null) {
                predicates.add(cb.equal(root.get("categoryCode"), category));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<BizContent> page = repository.findAll(spec,
                PageRequest.of(query.page() - 1, query.size(),
                        Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"))));
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }

    /** 搜索联想（FR-004 联想、QD-03）：标题匹配候选 ≤10。 */
    public List<BizContent> suggestions(String keyword) {
        String kw = requireKeyword(keyword);
        return repository.findByStatusAndTitleContainingIgnoreCase(ContentStatus.published, kw,
                PageRequest.of(0, SUGGESTION_LIMIT,
                        Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"))));
    }

    /** BR-012 服务端兜底：trim 后空关键词拒绝（business-content-02-0001）。 */
    private static String requireKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new BizException(ContentError.SEARCH_KEYWORD_EMPTY);
        }
        return keyword.trim();
    }
}
