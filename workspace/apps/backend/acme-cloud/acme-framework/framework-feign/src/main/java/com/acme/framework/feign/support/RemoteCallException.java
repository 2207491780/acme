package com.acme.framework.feign.support;

import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;

/**
 * 下游调用异常（FR-021 扩展，REQ-20261127-235505 确认⑥①方案 A）：在 {@link BizException} 基础上
 * 携带下游 HTTP 状态码，供消费方按状态码结构化判定（如 workflow 侧注销 404 幂等收敛），
 * 替代 message 字符串匹配。message 为 null 时沿用 {@code errorCode.defaultMessage()}（DP-007）；
 * 既有 BizException catch 面不受影响（子类兼容）。
 */
public class RemoteCallException extends BizException {

    private final int httpStatus;

    public RemoteCallException(ErrorCode errorCode, int httpStatus) {
        super(errorCode);
        this.httpStatus = httpStatus;
    }

    public RemoteCallException(ErrorCode errorCode, String message, int httpStatus) {
        super(errorCode, message);
        this.httpStatus = httpStatus;
    }

    /** 下游 HTTP 状态码（由 {@code FeignErrorDecoder} 自 feign Response.status() 携带）。 */
    public int httpStatus() {
        return httpStatus;
    }
}
