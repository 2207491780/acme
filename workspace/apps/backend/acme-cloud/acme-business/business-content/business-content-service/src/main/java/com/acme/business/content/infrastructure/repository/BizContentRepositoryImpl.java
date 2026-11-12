package com.acme.business.content.infrastructure.repository;

import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.mapper.BizContentMapper;
import com.acme.framework.flex.internal.FlexDeletes;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryMethods;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 内容仓库实现（MyBatis-Flex 底座；软删行由逻辑删列统一过滤，BR-015）。 */
@Repository
public class BizContentRepositoryImpl extends FlexRepositorySupport<BizContent>
        implements BizContentRepository {

    public BizContentRepositoryImpl(BizContentMapper mapper) {
        super(mapper);
    }

    @Override
    public List<String> findDistinctAuthors() {
        QueryWrapper query = QueryWrapper.create()
                .select(QueryMethods.distinct(BizContent::getAuthor))
                .orderBy(BizContent::getAuthor, true);
        return mapper().selectObjectListByQueryAs(query, String.class);
    }

    @Override
    public List<String> findDistinctAuthorsByCategory(String categoryCode) {
        QueryWrapper query = QueryWrapper.create()
                .select(QueryMethods.distinct(BizContent::getAuthor))
                .where(BizContent::getCategoryCode).eq(categoryCode)
                .orderBy(BizContent::getAuthor, true);
        return mapper().selectObjectListByQueryAs(query, String.class);
    }

    @Override
    public List<ContentRowState> findRowStatesIncludingDeleted(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // 行态探测等效（原生 SQL 语义）：旁路逻辑删过滤取全行；仅批量分类用（架构设计 6.2、BR-015）
        QueryWrapper query = QueryWrapper.create().where(new QueryColumn("id").in(ids));
        List<BizContent> rows = FlexDeletes.execWithoutLogicDelete(() -> mapper().selectListByQuery(query));
        return rows.stream()
                .map(row -> (ContentRowState) new ContentRowStateRow(row.getId(), row.getDeleted()))
                .toList();
    }

    /** 行态投影载体（id + deleted）。 */
    record ContentRowStateRow(Long id, Boolean deleted) implements ContentRowState {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public Boolean getDeleted() {
            return deleted;
        }
    }
}
