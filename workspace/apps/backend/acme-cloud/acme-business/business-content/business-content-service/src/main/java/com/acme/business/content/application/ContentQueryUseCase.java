package com.acme.business.content.application;

import com.acme.business.content.api.AdminSortField;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.api.FeedTab;
import com.acme.business.content.api.LibrarySort;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
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
import java.util.Optional;

/**
 * 内容查询用例（架构设计 2.1）：读侧（feed / 内容库 / 详情 / 相关推荐）与管理侧分页共享本查询面，
 * 端差异仅在接口层裁剪与权限（M-08、11.3.1）；本类不含权限判定（B 端逐端点 @RequirePermission）。
 */
@Service
public class ContentQueryUseCase {

    /** 推荐流定长上限（架构设计 7 取舍 #13：feed 定长列表，服务端限量）。 */
    static final int FEED_LIMIT = 20;

    /** 相关推荐上限（架构设计取舍 #10：≤6 条）。 */
    static final int RELATED_LIMIT = 6;

    private final BizContentRepository repository;

    public ContentQueryUseCase(BizContentRepository repository) {
        this.repository = repository;
    }

    /**
     * 推荐流（FR-001）：recommend 返回 published 内容流（最新优先，定长限量）；
     * follow/city/library 本期空集（QD-02）；tab 未知值空集不报错（FR-001 异常分支）。
     */
    public List<BizContent> feed(String tabRaw) {
        if (FeedTab.fromWire(tabRaw) != FeedTab.recommend) {
            return List.of();
        }
        return repository.findByStatus(ContentStatus.published,
                PageRequest.of(0, FEED_LIMIT, Sort.by(Sort.Direction.DESC, "updatedAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    /**
     * 内容库列表（FR-003）：仅 published（BR-002），分类可选；
     * newest=updated_at 倒序、hottest=views 存量值倒序（Q-14=B）。
     */
    public PageResult<BizContent> library(String categoryCode, LibrarySort sort, PageQuery query) {
        String category = normalize(categoryCode);
        Specification<BizContent> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), ContentStatus.published));
            if (category != null) {
                predicates.add(cb.equal(root.get("categoryCode"), category));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Sort order = sort == LibrarySort.hottest
                ? Sort.by(Sort.Direction.DESC, "views").and(Sort.by(Sort.Direction.ASC, "id"))
                : Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"));
        Page<BizContent> page = repository.findAll(spec,
                PageRequest.of(query.page() - 1, query.size(), order));
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }

    /**
     * 内容详情（FR-005）：仅 published；id 不存在 / 未发布返回空（openapi 空态口径，非 404）。
     */
    public Optional<BizContent> detail(Long id) {
        return repository.findById(id)
                .filter(content -> content.getStatus() == ContentStatus.published);
    }

    /** 相关推荐（FR-005）：同分类其他内容（published，最新优先，≤6，架构设计取舍 #10）。 */
    public List<BizContent> related(BizContent source) {
        return repository.findByStatusAndCategoryCodeAndIdNot(ContentStatus.published,
                source.getCategoryCode(), source.getId(),
                PageRequest.of(0, RELATED_LIMIT, Sort.by(Sort.Direction.DESC, "updatedAt")
                        .and(Sort.by(Sort.Direction.ASC, "id"))));
    }

    /**
     * B 端内容分页查询（FR-007）：keyword 匹配标题或作者（BR-005 不区分大小写）、
     * 分类 / 作者 / 状态筛选；title/views/updatedAt 三字段升降序（BR-007）；软删行不出现在结果（BR-015）。
     */
    public PageResult<BizContent> adminPage(String keyword, String categoryCode, String author,
                                            ContentStatus status, AdminSortField sortField,
                                            boolean sortAsc, PageQuery query) {
        String kw = normalize(keyword);
        String category = normalize(categoryCode);
        String authorFilter = normalize(author);
        Specification<BizContent> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (kw != null) {
                String pattern = LikePatterns.contains(kw);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("author")), pattern, '\\')));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("categoryCode"), category));
            }
            if (authorFilter != null) {
                predicates.add(cb.equal(root.get("author"), authorFilter));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        AdminSortField field = sortField == null ? AdminSortField.updatedAt : sortField;
        // 排序字段名与实体属性名一致（BR-007 限三者）；id 作稳定排序次键（9.4.1 不假设自然序）
        Sort order = Sort.by(sortAsc ? Sort.Direction.ASC : Sort.Direction.DESC, field.name())
                .and(Sort.by(Sort.Direction.ASC, "id"));
        Page<BizContent> page = repository.findAll(spec,
                PageRequest.of(query.page() - 1, query.size(), order));
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }

    private static String normalize(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : raw.trim();
    }
}
