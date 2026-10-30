package com.acme.business.content.api;

/**
 * 批量动作枚举（openapi BatchAction；FR-010，QD-05 单端点 + action 枚举）。
 * 常量名即 wire 形态（EnumCodeModule name() 兜底口径）。
 */
public enum BatchAction {

    /** 批量下线：无条件置 draft（BR-004 / QD-06 管理覆盖动作）。 */
    offline,

    /** 批量删除：软删（BR-015，被删行双端不可见）。 */
    delete
}
