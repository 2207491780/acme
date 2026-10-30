package com.acme.business.content.api;

/**
 * 内容域错误码文本常量（Backend.md 10.5.2，serviceId=business-content；架构设计 4.2 表）：
 * 完整枚举（含 HTTP 映射）归 business-content-service ContentError，两处取值一致由单测锁定。
 */
public final class ContentErrorCodes {

    /** 内容不存在（404；FR-008/009 路径 id 不存在；批量逐条失败明细复用）。 */
    public static final String CONTENT_NOT_FOUND = "business-content-01-0001";

    /** 标题不能为空（400；BR-003 领域层 rename 校验）。 */
    public static final String TITLE_EMPTY = "business-content-01-0002";

    /** 非法状态流转（409；BR-013 集合外迁移）。 */
    public static final String ILLEGAL_TRANSITION = "business-content-01-0003";

    // ---- 02 段：检索读侧 ----

    /** 搜索关键词为空（400；BR-012 服务端兜底校验）。 */
    public static final String SEARCH_KEYWORD_EMPTY = "business-content-02-0001";

    /** 推荐流 tab 不支持（400；码位预留——本期未知 tab 回空集不报错，FR-001，后续关系能力启用）。 */
    public static final String FEED_TAB_UNSUPPORTED = "business-content-02-0002";

    private ContentErrorCodes() {
    }
}
