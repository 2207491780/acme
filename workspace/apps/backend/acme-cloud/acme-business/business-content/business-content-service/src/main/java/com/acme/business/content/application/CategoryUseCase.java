package com.acme.business.content.application;

import com.acme.business.content.domain.BizContentCategory;
import com.acme.business.content.infrastructure.repository.BizContentCategoryRepository;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类用例（FR-006）：分类清单与分类名索引（B/C 同源，AC-006）；不含「全部」占位（需求 7.1）。
 * 作者候选（FR-011）同域承载：随分类联动的候选清单（BR-006 前端清空已选，服务端仅出候选）。
 */
@Service
public class CategoryUseCase {

    private final BizContentCategoryRepository categoryRepository;
    private final BizContentRepository contentRepository;

    public CategoryUseCase(BizContentCategoryRepository categoryRepository, BizContentRepository contentRepository) {
        this.categoryRepository = categoryRepository;
        this.contentRepository = contentRepository;
    }

    /** 分类清单（FR-006；sortOrder 升序）。 */
    public List<BizContentCategory> list() {
        return categoryRepository.findAllByOrderBySortOrderAscCodeAsc();
    }

    /** 分类名索引（code → name；一次批查供视图转换，6.4 #3 禁行内逐条查）。 */
    public Map<String, String> nameIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (BizContentCategory category : list()) {
            index.put(category.getCode(), category.getName());
        }
        return index;
    }

    /** 作者候选（FR-011；去重、稳定排序；分类可选）。 */
    public List<String> authors(String categoryCode) {
        if (categoryCode == null || categoryCode.trim().isEmpty()) {
            return contentRepository.findDistinctAuthors();
        }
        return contentRepository.findDistinctAuthorsByCategory(categoryCode.trim());
    }
}
