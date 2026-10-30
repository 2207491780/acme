package com.acme.framework.jpa.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-jpa 错误码枚举（Q-D06：SYS-04 段，序号 0001 起，DP-008 定稿后回填）。
 */
public enum JpaError implements ErrorCode, HttpStatusAware {

    /** 批量写入失败（FR-012 异常分支，6.6）。 */
    BATCH_FAILED("SYS-04-0001", "批量写入失败", 500);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    JpaError(String code, String defaultMessage, int httpStatus) {
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
