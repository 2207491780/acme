package com.acme.framework.web.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-web 错误码枚举（Q-D06：SYS-01 段，序号 0001 起，DP-008 定稿后回填）。
 */
public enum WebError implements ErrorCode, HttpStatusAware {

    /** 参数校验失败（FR-005）。 */
    VALIDATION_FAILED("SYS-01-0001", "参数校验失败", 400),

    /** 请求不合法（10.5.3）。 */
    BAD_REQUEST("SYS-01-0002", "请求参数不合法", 400),

    /** 并发冲突（FR-010，5.2 映射 409）。 */
    CONCURRENCY_CONFLICT("SYS-01-0003", "数据版本冲突，请刷新后重试", 409),

    /** 请求方法不支持（10.5.3）。 */
    METHOD_NOT_ALLOWED("SYS-01-0004", "请求方法不支持", 405),

    /** 请求内容类型不支持（10.5.3）。 */
    MEDIA_TYPE_NOT_SUPPORTED("SYS-01-0005", "请求内容类型不支持", 415),

    /** 响应内容类型不可接受（10.5.3）。 */
    MEDIA_TYPE_NOT_ACCEPTABLE("SYS-01-0006", "响应内容类型不可接受", 406);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    WebError(String code, String defaultMessage, int httpStatus) {
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
