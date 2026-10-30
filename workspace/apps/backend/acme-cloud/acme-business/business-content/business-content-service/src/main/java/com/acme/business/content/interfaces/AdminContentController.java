package com.acme.business.content.interfaces;

import com.acme.business.content.api.AdminSortField;
import com.acme.business.content.api.ContentPermissions;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.api.dto.AdminContentBatchCommand;
import com.acme.business.content.api.dto.AdminContentBatchResultView;
import com.acme.business.content.api.dto.AdminContentRowView;
import com.acme.business.content.api.dto.AdminContentStatusTransition;
import com.acme.business.content.api.dto.AdminContentTitleUpdate;
import com.acme.business.content.application.CategoryUseCase;
import com.acme.business.content.application.ContentAdminUseCase;
import com.acme.business.content.application.ContentQueryUseCase;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.security.RequirePermission;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * B 端内容管理接口（FR-007～FR-011；认证 + 权限点逐端点 @RequirePermission，架构设计 6.5）。
 * 批量端点权限按 action 动态判定（QD-05），在应用层编排（M-05 事务外）。
 */
@RestController
@RequestMapping("/admin/v1")
public class AdminContentController {

    private final ContentQueryUseCase contentQueryUseCase;
    private final ContentAdminUseCase contentAdminUseCase;
    private final CategoryUseCase categoryUseCase;

    public AdminContentController(ContentQueryUseCase contentQueryUseCase,
                                  ContentAdminUseCase contentAdminUseCase,
                                  CategoryUseCase categoryUseCase) {
        this.contentQueryUseCase = contentQueryUseCase;
        this.contentAdminUseCase = contentAdminUseCase;
        this.categoryUseCase = categoryUseCase;
    }

    /** 内容分页查询（FR-007）：keyword 匹配标题或作者（BR-005）、三字段升降序（BR-007）。 */
    @GetMapping("/contents")
    @RequirePermission(ContentPermissions.CONTENT_LIST)
    public PageResult<AdminContentRowView> list(
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) @Size(max = 32) String categoryCode,
            @RequestParam(required = false) @Size(max = 64) String author,
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(defaultValue = "updatedAt") AdminSortField sortField,
            @RequestParam(defaultValue = "false") Boolean sortAsc,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) Integer size) {
        PageResult<BizContent> result = contentQueryUseCase.adminPage(
                keyword, categoryCode, author, status, sortField, Boolean.TRUE.equals(sortAsc),
                new PageQuery(page, size));
        Map<String, String> names = categoryUseCase.nameIndex();
        return new PageResult<>(
                result.records().stream().map(content -> ContentViews.toAdminRow(content, names)).toList(),
                result.total());
    }

    /** 作者候选获取（FR-011；BR-006 联动由前端清空已选）。 */
    @GetMapping("/contents/authors")
    @RequirePermission(ContentPermissions.CONTENT_LIST)
    public List<String> authors(@RequestParam(required = false) @Size(max = 32) String categoryCode) {
        return categoryUseCase.authors(categoryCode);
    }

    /** 标题行内修改（FR-008）：空标题 400 business-content-01-0002；并发冲突 409 SYS-01-0003（QD-04）。 */
    @PatchMapping("/contents/{contentId}/title")
    @RequirePermission(ContentPermissions.CONTENT_UPDATE)
    public AdminContentRowView updateTitle(@PathVariable String contentId,
                                           @Valid @RequestBody AdminContentTitleUpdate request) {
        Long id = PathIds.parseRequired(contentId);
        BizContent updated = contentAdminUseCase.renameTitle(id, request.title(), request.expectedVersion());
        return ContentViews.toAdminRow(updated, categoryUseCase.nameIndex());
    }

    /** 状态流转（FR-009）：同态幂等；集合外迁移 409 business-content-01-0003（BR-013）。 */
    @PutMapping("/contents/{contentId}/status")
    @RequirePermission(ContentPermissions.CONTENT_TRANSITION)
    public AdminContentRowView transitionStatus(@PathVariable String contentId,
                                                @Valid @RequestBody AdminContentStatusTransition request) {
        Long id = PathIds.parseRequired(contentId);
        BizContent updated = contentAdminUseCase.transitionStatus(id, request.targetStatus());
        return ContentViews.toAdminRow(updated, categoryUseCase.nameIndex());
    }

    /** 批量下线 / 批量删除（FR-010）：权限按 action 判定（QD-05）；逐条结果、不整批回滚。 */
    @PostMapping("/contents/batch")
    public AdminContentBatchResultView batch(@Valid @RequestBody AdminContentBatchCommand command) {
        return contentAdminUseCase.batch(command.action(), command.ids());
    }
}
