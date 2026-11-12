package com.acme.business.content.application;

import com.acme.business.content.api.AdminSortField;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.api.FeedTab;
import com.acme.business.content.api.LibrarySort;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryMethods;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

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
        return repository.findAll(QueryWrapper.create()
                .where(BizContent::getStatus).eq(ContentStatus.published)
                .orderBy(BizContent::getUpdatedAt, false)
                .orderBy(BizContent::getId, false)
                .limit(FEED_LIMIT));
    }

    /**
     * 内容库列表（FR-003）：仅 published（BR-002），分类可选；
     * newest=updated_at 倒序、hottest=views 存量值倒序（Q-14=B）。
     */
    public PageResult<BizContent> library(String categoryCode, LibrarySort sort, PageQuery query) {
        String category = normalize(categoryCode);
        QueryWrapper wrapper = QueryWrapper.create()
                .where(BizContent::getStatus).eq(ContentStatus.published);
        if (category != null) {
            wrapper.and(BizContent::getCategoryCode).eq(category);
        }
        if (sort == LibrarySort.hottest) {
            wrapper.orderBy(BizContent::getViews, false);
        } else {
            wrapper.orderBy(BizContent::getUpdatedAt, false);
        }
        wrapper.orderBy(BizContent::getId, true);
        return repository.findAll(wrapper, query.page(), query.size());
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
        return repository.findAll(QueryWrapper.create()
                .where(BizContent::getStatus).eq(ContentStatus.published)
                .and(BizContent::getCategoryCode).eq(source.getCategoryCode())
                .and(BizContent::getId).ne(source.getId())
                .orderBy(BizContent::getUpdatedAt, false)
                .orderBy(BizContent::getId, true)
                .limit(RELATED_LIMIT));
    }

    /**
     * B 端内容分页查询（FR-007）：keyword 匹配标题或作者（BR-005 不区分大小写、通配符转义），
     * 分类 / 作者 / 状态筛选；title/views/updatedAt 三字段升降序（BR-007）；软删行不出现在结果（BR-015）。
     */
    public PageResult<BizContent> adminPage(String keyword, String categoryCode, String author,
                                            ContentStatus status, AdminSortField sortField,
                                            boolean sortAsc, PageQuery query) {
        String kw = normalize(keyword);
        String category = normalize(categoryCode);
        String authorFilter = normalize(author);
        // Flex 首条件 where/and 等效（BaseQueryWrapper 头节点判定），统一 and 链接，无需首条件标志位
        QueryWrapper wrapper = QueryWrapper.create();
        if (kw != null) {
            // PG LIKE 默认反斜杠转义（LikePatterns 含 % 转义，按字面匹配）；OR 组作为单一条件树保持优先级
            String pattern = LikePatterns.contains(kw);
            wrapper.and(QueryMethods.lower(BizContent::getTitle).like(pattern)
                    .or(QueryMethods.lower(BizContent::getAuthor).like(pattern)));
        }
        if (category != null) {
            wrapper.and(BizContent::getCategoryCode).eq(category);
        }
        if (authorFilter != null) {
            wrapper.and(BizContent::getAuthor).eq(authorFilter);
        }
        if (status != null) {
            wrapper.and(BizContent::getStatus).eq(status);
        }
        AdminSortField field = sortField == null ? AdminSortField.updatedAt : sortField;
        // 排序字段 BR-007 限三者，列名与实体属性一致；id 作稳定排序次键（9.4.1 不假设自然序）
        wrapper.orderBy(sortColumn(field), sortAsc).orderBy(BizContent::getId, true);
        return repository.findAll(wrapper, query.page(), query.size());
    }

    private static QueryColumn sortColumn(AdminSortField field) {
        return switch (field) {
            case title -> new QueryColumn("title");
            case views -> new QueryColumn("views");
            case updatedAt -> new QueryColumn("updated_at");
        };
    }

    private static String normalize(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : raw.trim();
    }
}
