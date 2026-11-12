package com.acme.framework.stream.support;

import com.acme.common.core.ErrorCode;

/**
 * framework-stream 错误码枚举（Q-D06 同型续编：SYS-08 段，DP-008 定稿后回填）。
 */
public enum StreamError implements ErrorCode {

    /** 信封/协议字段非法（FR-013、DP-009）。 */
    ENVELOPE_INVALID("SYS-08-0001", "消息协议字段非法"),

    /** 发送失败（FR-013/017）。 */
    SEND_FAILED("SYS-08-0002", "消息发送失败"),

    /** 去重存储不可用（FR-014 异常分支 fail-closed）。 */
    DEDUP_STORE_UNAVAILABLE("SYS-08-0003", "消息去重存储不可用"),

    /** 死信通道未配置（FR-015 异常分支）。 */
    DLQ_NOT_CONFIGURED("SYS-08-0004", "消费主题未配置死信通道");

    private final String code;
    private final String defaultMessage;

    StreamError(String code, String defaultMessage) {
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
