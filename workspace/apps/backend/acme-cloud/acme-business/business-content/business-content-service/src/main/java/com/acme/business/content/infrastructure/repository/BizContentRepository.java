package com.acme.business.content.infrastructure.repository;

import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.domain.BizContent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 内容仓库（biz_content；软删行由 AuditBase @SQLRestriction 统一过滤，BR-015）。
 * 动态条件查询走 {@link JpaSpecificationExecutor}（platform-infra AuditQueryUseCase 同型先例）。
 */
public interface BizContentRepository extends JpaRepository<BizContent, Long>, JpaSpecificationExecutor<BizContent> {

    /** 推荐流（BR-002 仅 published，最新优先；定长限量由调用方 Pageable 决定）。 */
    List<BizContent> findByStatus(ContentStatus status, Pageable pageable);

    /** 相关推荐（同分类、排除自身、published、最新优先，架构设计取舍 #10）。 */
    List<BizContent> findByStatusAndCategoryCodeAndIdNot(ContentStatus status, String categoryCode, Long id,
                                                         Pageable pageable);

    /** 搜索联想（标题匹配候选 ≤10，QD-03；BR-002 仅 published）。 */
    List<BizContent> findByStatusAndTitleContainingIgnoreCase(ContentStatus status, String keyword, Pageable pageable);

    /** 作者候选（FR-011；去重、稳定排序）。 */
    @Query("select distinct c.author from BizContent c where c.categoryCode = :categoryCode order by c.author")
    List<String> findDistinctAuthorsByCategory(@Param("categoryCode") String categoryCode);

    /** 作者候选（无分类过滤，FR-011）。 */
    @Query("select distinct c.author from BizContent c order by c.author")
    List<String> findDistinctAuthors();

    /**
     * 批量行态探测（含软删行）：区分「从未存在」与「已删」（架构设计 6.2 统一口径）。
     * 原生查询不受 @SQLRestriction 过滤；仅批量分类用，常规查询不得绕过软删过滤（BR-015）。
     */
    @Query(value = "select id, deleted from biz_content where id in (:ids)", nativeQuery = true)
    List<ContentRowState> findRowStatesIncludingDeleted(@Param("ids") Collection<Long> ids);

    /** 行态投影（id + deleted）。 */
    interface ContentRowState {

        Long getId();

        Boolean getDeleted();
    }
}
