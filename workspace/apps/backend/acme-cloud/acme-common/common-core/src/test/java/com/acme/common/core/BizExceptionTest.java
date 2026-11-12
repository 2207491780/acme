package com.acme.common.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BizException 契约级语义测试（AC-005、架构设计 6.2）：携带 ErrorCode（Backend.md 10.5.1、BR-018）。
 */
class BizExceptionTest {

    @Test
    void carriesErrorCodeAndDefaultMessage() {
        BizException e = new BizException(TestErrorCodes.DEMO);
        assertEquals(TestErrorCodes.DEMO, e.errorCode());
        assertEquals(TestErrorCodes.DEMO.defaultMessage(), e.getMessage());
    }

    @Test
    void carriesExplicitMessageAndCause() {
        Throwable cause = new IllegalStateException("cause");
        BizException e = new BizException(TestErrorCodes.DEMO, "自定义提示", cause);
        assertEquals(TestErrorCodes.DEMO, e.errorCode());
        assertEquals("自定义提示", e.getMessage());
        assertEquals(cause, e.getCause());
    }
}
