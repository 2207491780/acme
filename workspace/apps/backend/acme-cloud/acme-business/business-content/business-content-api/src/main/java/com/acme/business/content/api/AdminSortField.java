package com.acme.business.content.api;

/**
 * B 端排序字段枚举（openapi AdminSortField；BR-007 限三者，各支持升降序）。
 * 常量名即 wire 形态（EnumCodeModule name() 兜底口径）。
 */
public enum AdminSortField {

    /** 标题。 */
    title,

    /** 浏览量存量值（Q-14=B）。 */
    views,

    /** 更新时间（缺省）。 */
    updatedAt
}
