package com.acme.framework.feign.support;

import com.acme.common.core.ErrorCode;

/**
 * framework-feign 错误码枚举（Q-D06 同型续编：SYS-09 段，DP-008 定稿后回填）。
 */
public enum FeignError implements ErrorCode {

    /** 调用失败（FR-021/018）。 */
    CALL_FAILED("SYS-09-0001", "内部调用失败"),

    /** 调用超时（FR-018 异常分支）。 */
    TIMEOUT("SYS-09-0002", "内部调用超时"),

    /** 熔断开启（FR-020）。 */
    CIRCUIT_OPEN("SYS-09-0003", "内部调用熔断中"),

    /** 令牌签发失败（FR-022 异常分支：不发无令牌调用）。 */
    TOKEN_ISSUE_FAILED("SYS-09-0004", "内部令牌签发失败");

    private final String code;
    private final String defaultMessage;

    FeignError(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
