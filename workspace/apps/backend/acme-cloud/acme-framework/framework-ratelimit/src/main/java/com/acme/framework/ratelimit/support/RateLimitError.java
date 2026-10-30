package com.acme.framework.ratelimit.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-ratelimit 错误码枚举（SYS-17 段，序号 0001 起，DP-008 段位锁定）。
 */
public enum RateLimitError implements ErrorCode, HttpStatusAware {

    /** 超限拒绝（FR-018：429，无业务兜底数据）。 */
    RATE_LIMITED("SYS-17-0001", "请求过于频繁，请稍后重试", 429);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    RateLimitError(String code, String defaultMessage, int httpStatus) {
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
