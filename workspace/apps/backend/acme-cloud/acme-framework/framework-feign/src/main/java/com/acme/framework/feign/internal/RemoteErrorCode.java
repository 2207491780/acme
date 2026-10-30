package com.acme.framework.feign.internal;

import com.acme.common.core.ErrorCode;

/**
 * 下游错误码承载（internal，FR-021）：携带下游错误码与面向调用方提示；
 * 不透传堆栈/SQL/内部地址（10.5.4）。
 */
public record RemoteErrorCode(String code, String defaultMessage) implements ErrorCode {

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
