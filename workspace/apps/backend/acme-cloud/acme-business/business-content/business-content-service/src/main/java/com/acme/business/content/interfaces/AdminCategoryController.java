package com.acme.business.content.interfaces;

import com.acme.business.content.api.ContentPermissions;
import com.acme.business.content.api.dto.AdminCategoryView;
import com.acme.business.content.application.CategoryUseCase;
import com.acme.business.content.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端分类接口（FR-006，B 端下拉）：与 C 端 GET /app/v1/categories 同源（AC-006），视图对象分离（11.5.1）。
 */
@RestController
@RequestMapping("/admin/v1")
public class AdminCategoryController {

    private final CategoryUseCase categoryUseCase;

    public AdminCategoryController(CategoryUseCase categoryUseCase) {
        this.categoryUseCase = categoryUseCase;
    }

    /** 分类清单（FR-006；sortOrder 升序）。 */
    @GetMapping("/categories")
    @RequirePermission(ContentPermissions.CONTENT_LIST)
    public List<AdminCategoryView> categories() {
        return categoryUseCase.list().stream().map(ContentViews::toAdminCategory).toList();
    }
}
