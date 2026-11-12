package com.acme.business.content.interfaces;

import com.acme.business.content.api.dto.AppCategoryView;
import com.acme.business.content.application.CategoryUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端分类接口（FR-006，公开可读 Q-12=A）：分类清单不含「全部」占位（需求 7.1）；
 * 与 B 端 GET /admin/v1/categories 同源（AC-006）。
 */
@RestController
@RequestMapping("/app/v1")
public class AppCategoryController {

    private final CategoryUseCase categoryUseCase;

    public AppCategoryController(CategoryUseCase categoryUseCase) {
        this.categoryUseCase = categoryUseCase;
    }

    /** 分类清单（FR-006；sortOrder 升序）。 */
    @GetMapping("/categories")
    public List<AppCategoryView> categories() {
        return categoryUseCase.list().stream().map(ContentViews::toAppCategory).toList();
    }
}
