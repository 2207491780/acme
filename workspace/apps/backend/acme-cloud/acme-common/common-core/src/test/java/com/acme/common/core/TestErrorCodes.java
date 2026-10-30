package com.acme.common.core;

/**
 * 测试用错误码（仅供本模块单测）。
 */
enum TestErrorCodes implements ErrorCode {

    DEMO("test-10-0001", "演示错误");

    private final String code;
    private final String defaultMessage;

    TestErrorCodes(String code, String defaultMessage) {
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
