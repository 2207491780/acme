package com.acme.business.content.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 热搜词（架构设计 3.4；biz_hot_search_word）：词表实体 + 本期种子落库（Q-06 定稿，写路径/维护页后续批）。
 * 接口 ORDER BY sort_order ASC（BR-014 前端不重排）。
 */
@Table("biz_hot_search_word")
public class BizHotSearchWord extends AuditBase {

    @Column("keyword")
    private String keyword;

    @Column("sort_order")
    private Integer sortOrder;

    protected BizHotSearchWord() {
    }

    public BizHotSearchWord(String keyword, Integer sortOrder) {
        this.keyword = keyword;
        this.sortOrder = sortOrder;
    }

    public String getKeyword() {
        return keyword;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
