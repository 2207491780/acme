package com.acme.business.content.api;

/**
 * 内容库排序枚举（openapi LibrarySort；FR-003）。
 * 常量名即 wire 形态（EnumCodeModule name() 兜底口径）。
 */
public enum LibrarySort {

    /** 最新（updated_at 倒序，缺省）。 */
    newest,

    /** 最热（views 存量值倒序，Q-14=B）。 */
    hottest
}
