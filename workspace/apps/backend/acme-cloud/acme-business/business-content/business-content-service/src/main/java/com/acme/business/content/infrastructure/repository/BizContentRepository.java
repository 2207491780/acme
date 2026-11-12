package com.acme.business.content.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.business.content.domain.BizContent;

import java.util.Collection;
import java.util.List;

/**
 * 内容仓库（biz_content；软删行由逻辑删列统一过滤，BR-015）。
 * 动态条件查询经 {@link FlexRepository#findAll(com.mybatisflex.core.query.QueryWrapper, Integer, Integer)}。
 */
public interface BizContentRepository extends FlexRepository<BizContent> {

    /** 作者候选（FR-011；去重、稳定排序）。 */
    List<String> findDistinctAuthorsByCategory(String categoryCode);

    /** 作者候选（无分类过滤，FR-011）。 */
    List<String> findDistinctAuthors();

    /**
     * 批量行态探测（含软删行）：区分「从未存在」与「已删」（架构设计 6.2 统一口径）。
     * 旁路查询不受逻辑删过滤；仅批量分类用，常规查询不得绕过软删过滤（BR-015）。
     */
    List<ContentRowState> findRowStatesIncludingDeleted(Collection<Long> ids);

    /** 行态投影（id + deleted）。 */
    interface ContentRowState {

        Long getId();

        Boolean getDeleted();
    }
}
