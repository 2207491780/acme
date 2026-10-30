package com.acme.business.content.interfaces;

import com.acme.business.content.api.dto.AppContentItemView;
import com.acme.business.content.api.dto.AppSearchSuggestionView;
import com.acme.business.content.application.CategoryUseCase;
import com.acme.business.content.application.SearchUseCase;
import com.acme.business.content.domain.BizContent;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * C 端检索接口（FR-002/004；公开可读 Q-12=A）：热搜榜 / 搜索结果 / 搜索联想。
 * keyword trim 后须非空（BR-012，服务端兜底 business-content-02-0001）。
 */
@RestController
@RequestMapping("/app/v1")
public class AppSearchController {

    private final SearchUseCase searchUseCase;
    private final CategoryUseCase categoryUseCase;

    public AppSearchController(SearchUseCase searchUseCase, CategoryUseCase categoryUseCase) {
        this.searchUseCase = searchUseCase;
        this.categoryUseCase = categoryUseCase;
    }

    /** 热搜榜（FR-002）：服务端顺序（BR-014 前端不重排）。 */
    @GetMapping("/hot-searches")
    public List<String> hotSearches() {
        return searchUseCase.hotSearches();
    }

    /** 搜索结果（FR-004）：标题/摘要匹配，分页（QD-10，PageQuery/PageResult 协议）。 */
    @GetMapping("/search")
    public PageResult<AppContentItemView> search(
            @RequestParam @Size(max = 64) String keyword,
            @RequestParam(required = false) @Size(max = 32) String categoryCode,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) Integer size) {
        PageResult<BizContent> result = searchUseCase.search(keyword, categoryCode, new PageQuery(page, size));
        Map<String, String> names = categoryUseCase.nameIndex();
        return new PageResult<>(
                result.records().stream().map(content -> ContentViews.toAppItem(content, names)).toList(),
                result.total());
    }

    /** 搜索联想（FR-004 联想，QD-03）：标题匹配候选 ≤10。 */
    @GetMapping("/search/suggestions")
    public List<AppSearchSuggestionView> suggestions(@RequestParam @Size(max = 64) String keyword) {
        return searchUseCase.suggestions(keyword).stream().map(ContentViews::toSuggestion).toList();
    }
}
