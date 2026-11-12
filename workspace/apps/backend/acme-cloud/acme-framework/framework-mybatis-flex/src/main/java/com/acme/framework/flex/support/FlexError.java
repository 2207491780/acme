package com.acme.framework.flex.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-mybatis-flex 错误码枚举（Q-D06：SYS-04 段；SYS-04-0001 槽位随 BatchWriter 移除释放，
 * 乐观锁冲突自 SYS-04-0002 起）。
 */
public enum FlexError implements ErrorCode, HttpStatusAware {

    /** 乐观锁冲突（FR-010 异常分支）：按实体更新 0 行=版本已被并发修改。 */
    STALE_WRITE("SYS-04-0002", "数据已被其他操作修改，请刷新后重试", 409);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    FlexError(String code, String defaultMessage, int httpStatus) {
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
