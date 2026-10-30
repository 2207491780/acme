package com.acme.framework.jpa.support;

/**
 * 查询条件运算符（FR-011 查询深度=POC-1 观测量口径：条件 + 分页 + 排序）。
 */
public enum Op {

    /** 等于。 */
    EQ,

    /** 不等于。 */
    NE,

    /** 大于等于。 */
    GE,

    /** 大于。 */
    GT,

    /** 小于等于。 */
    LE,

    /** 小于。 */
    LT,

    /** 前缀匹配。 */
    STARTS_WITH,

    /** 后缀匹配。 */
    ENDS_WITH,

    /** 包含匹配。 */
    CONTAINS,

    /** 集合包含。 */
    IN,

    /** 为空。 */
    IS_NULL,

    /** 不为空。 */
    IS_NOT_NULL
}
