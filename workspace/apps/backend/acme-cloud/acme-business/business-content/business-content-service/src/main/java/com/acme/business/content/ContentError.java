package com.acme.business.content;

import com.acme.business.content.api.ContentErrorCodes;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * 内容域错误码枚举（10.5.2 三段式，serviceId=business-content；架构设计 4.2 表）。
 * 码值与 business-content-api ContentErrorCodes 常量一一对应（契约测试锁定）。
 */
public enum ContentError implements ErrorCode, HttpStatusAware {

    /** 内容不存在（404；FR-008/009 路径 id 不存在，批量逐条失败明细复用）。 */
    CONTENT_NOT_FOUND(ContentErrorCodes.CONTENT_NOT_FOUND, "内容不存在", 404),

    /** 标题不能为空（400；BR-003）。 */
    TITLE_EMPTY(ContentErrorCodes.TITLE_EMPTY, "标题不能为空", 400),

    /** 非法状态流转（409；BR-013 集合外迁移）。 */
    ILLEGAL_TRANSITION(ContentErrorCodes.ILLEGAL_TRANSITION, "非法状态流转", 409),

    // ---- 02 段：检索读侧 ----

    /** 搜索关键词为空（400；BR-012 服务端兜底）。 */
    SEARCH_KEYWORD_EMPTY(ContentErrorCodes.SEARCH_KEYWORD_EMPTY, "搜索关键词不能为空", 400),

    /** 推荐流 tab 不支持（400；码位预留，本期未知 tab 回空集不报错，FR-001）。 */
    FEED_TAB_UNSUPPORTED(ContentErrorCodes.FEED_TAB_UNSUPPORTED, "推荐流 tab 不支持", 400);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    ContentError(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
