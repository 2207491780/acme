package com.acme.framework.idempotent.support;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * framework-idempotent 错误码枚举（SYS-11 段，序号 0001 起，DP-008 段位锁定）。
 */
public enum IdempotentError implements ErrorCode, HttpStatusAware {

    /** 幂等 token 缺失（REJECT 口径，@Idempotent 语义不被静默绕过）。 */
    MISSING_TOKEN("SYS-11-0001", "缺少幂等令牌", 400),

    /** 在途重复请求（REJECT 或 WAIT 超时，Q-D002）。 */
    IN_FLIGHT("SYS-11-0002", "请求处理中，请稍后重试", 409),

    /** 幂等存储不可用（fail-closed，FR-001 不得静默放行导致重复执行）。 */
    STORE_UNAVAILABLE("SYS-11-0003", "幂等服务暂不可用", 503);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    IdempotentError(String code, String defaultMessage, int httpStatus) {
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
