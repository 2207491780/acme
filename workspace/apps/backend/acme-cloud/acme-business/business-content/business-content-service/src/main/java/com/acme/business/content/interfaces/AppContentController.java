package com.acme.business.content.interfaces;

import com.acme.business.content.api.LibrarySort;
import com.acme.business.content.api.dto.AppContentDetailView;
import com.acme.business.content.api.dto.AppContentItemView;
import com.acme.business.content.application.CategoryUseCase;
import com.acme.business.content.application.ContentQueryUseCase;
import com.acme.business.content.domain.BizContent;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C 端内容消费接口（FR-001/003/005；公开可读 Q-12=A）：
 * 推荐流 / 内容库 / 详情（含相关推荐内嵌）；仅 published（BR-002）。
 */
@RestController
@RequestMapping("/app/v1")
public class AppContentController {

    private final ContentQueryUseCase contentQueryUseCase;
    private final CategoryUseCase categoryUseCase;

    public AppContentController(ContentQueryUseCase contentQueryUseCase, CategoryUseCase categoryUseCase) {
        this.contentQueryUseCase = contentQueryUseCase;
        this.categoryUseCase = categoryUseCase;
    }

    /** 推荐流（FR-001）：tab 未知值空集不报错（QD-02 / FR-001 异常分支）。 */
    @GetMapping("/feeds")
    public List<AppContentItemView> feed(@RequestParam String tab) {
        List<BizContent> rows = contentQueryUseCase.feed(tab);
        Map<String, String> names = categoryUseCase.nameIndex();
        return rows.stream().map(content -> ContentViews.toAppItem(content, names)).toList();
    }

    /** 内容库列表（FR-003）：分类筛选 + 排序 + 分页（QD-10，PageQuery/PageResult 协议）。 */
    @GetMapping("/contents")
    public PageResult<AppContentItemView> library(
            @RequestParam(required = false) @Size(max = 32) String categoryCode,
            @RequestParam(defaultValue = "newest") LibrarySort sort,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) Integer size) {
        PageResult<BizContent> result =
                contentQueryUseCase.library(categoryCode, sort, new PageQuery(page, size));
        Map<String, String> names = categoryUseCase.nameIndex();
        return new PageResult<>(
                result.records().stream().map(content -> ContentViews.toAppItem(content, names)).toList(),
                result.total());
    }

    /** 内容详情（FR-005，含相关推荐）：id 不存在 / 未发布返回 null（openapi 空态口径，非 404）。 */
    @GetMapping("/contents/{contentId}")
    public AppContentDetailView detail(@PathVariable String contentId) {
        Long id = PathIds.parseLenient(contentId);
        if (id == null) {
            return null;
        }
        Optional<BizContent> content = contentQueryUseCase.detail(id);
        if (content.isEmpty()) {
            return null;
        }
        List<BizContent> related = contentQueryUseCase.related(content.get());
        return ContentViews.toAppDetail(content.get(), related, categoryUseCase.nameIndex());
    }
}
