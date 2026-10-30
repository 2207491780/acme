package com.acme.business.content.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 热搜词（架构设计 3.4；biz_hot_search_word）：词表实体 + 本期种子落库（Q-06 定稿，写路径/维护页后续批）。
 * 接口 ORDER BY sort_order ASC（BR-014 前端不重排）。
 */
@Entity
@Table(name = "biz_hot_search_word",
       uniqueConstraints = @UniqueConstraint(name = "uk_biz_hot_search_keyword", columnNames = "keyword"))
public class BizHotSearchWord extends AuditBase {

    @Column(name = "keyword", length = 64, nullable = false)
    private String keyword;

    @Column(name = "sort_order", nullable = false)
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
