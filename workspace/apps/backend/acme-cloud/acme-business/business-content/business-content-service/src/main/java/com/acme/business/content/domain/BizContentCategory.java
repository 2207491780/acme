package com.acme.business.content.domain;

import com.acme.framework.flex.support.AuditBase;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 内容分类（架构设计 3.3；biz_content_category）：编码 + 名称 + 排序（QD-09 编码+名称定稿）。
 * 无软删标注（本期无分类删除入口，删除能力后续批）；分类清单不含「全部」占位（需求 7.1）。
 */
@Table("biz_content_category")
public class BizContentCategory extends AuditBase {

    @Column("code")
    private String code;

    @Column("name")
    private String name;

    @Column("sort_order")
    private Integer sortOrder;

    protected BizContentCategory() {
    }

    public BizContentCategory(String code, String name, Integer sortOrder) {
        this.code = code;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
