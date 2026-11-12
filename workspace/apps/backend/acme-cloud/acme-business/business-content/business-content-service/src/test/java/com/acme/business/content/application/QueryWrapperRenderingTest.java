package com.acme.business.content.application;

import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.domain.BizContent;
import com.mybatisflex.core.query.QueryMethods;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryWrapper 渲染锁定测试（BR-005/BR-012 等效语义）：OR 组作为单一条件树必须整体加括号，
 * 防 AND 优先级高于 OR 导致「状态过滤 OR 正文命中」的语义漂移（JPA cb.or 等效口径）。
 */
class QueryWrapperRenderingTest {

    @Test
    void orGroupStaysParenthesizedBesideTypedCondition() {
        // search 同型：status = published AND (lower(title) LIKE ? OR lower(excerpt) LIKE ?)
        QueryWrapper wrapper = QueryWrapper.create()
                .where(BizContent::getStatus).eq(ContentStatus.published)
                .and(QueryMethods.lower(BizContent::getTitle).like("%x%")
                        .or(QueryMethods.lower(BizContent::getExcerpt).like("%y%")));
        // toSQL 内联参数值；统一小写、剥离反引号后断言 OR 组括号结构（防 AND 优先级漂移）
        String sql = wrapper.toSQL().toLowerCase().replace("`", "");
        assertThat(sql).contains("status = published and (lower(title) like '%%x%%' or lower(excerpt) like '%%y%%')");
    }

    @Test
    void orGroupRenderedBeforeAndChainsKeepsPriority() {
        // adminPage keyword 同型：(lower(title) LIKE ? OR lower(author) LIKE ?) AND category = ?
        QueryWrapper wrapper = QueryWrapper.create()
                .where(QueryMethods.lower(BizContent::getTitle).like("%x%")
                        .or(QueryMethods.lower(BizContent::getAuthor).like("%y%")))
                .and(BizContent::getCategoryCode).eq("news");
        String sql = wrapper.toSQL().toLowerCase().replace("`", "");
        assertThat(sql).contains("(lower(title) like '%%x%%' or lower(author) like '%%y%%') and category_code = 'news'");
    }
}
