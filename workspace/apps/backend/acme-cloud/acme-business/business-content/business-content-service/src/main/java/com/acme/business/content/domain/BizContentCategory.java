package com.acme.business.content.domain;

import com.acme.framework.jpa.support.AuditBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 内容分类（架构设计 3.3；biz_content_category）：编码 + 名称 + 排序（QD-09 编码+名称定稿）。
 * 无 @SoftDelete 标注（本期无分类删除入口，删除能力后续批）；分类清单不含「全部」占位（需求 7.1）。
 */
@Entity
@Table(name = "biz_content_category",
       uniqueConstraints = @UniqueConstraint(name = "uk_biz_content_category_code", columnNames = "code"))
public class BizContentCategory extends AuditBase {

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "sort_order", nullable = false)
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
