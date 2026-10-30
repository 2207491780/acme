package com.acme.framework.gateway.internal;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;

/**
 * 网关自身拒绝信号（FR-019/022/025）：过滤链抛出，由 GatewayErrorWebExceptionHandler 统一封装（8.8）。
 */
public class GatewayRejectedException extends RuntimeException {

    private final ErrorCode errorCode;
    private final int httpStatus;

    public GatewayRejectedException(ErrorCode errorCode) {
        super(errorCode == null ? null : errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.httpStatus = errorCode instanceof HttpStatusAware aware ? aware.httpStatus() : 400;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
