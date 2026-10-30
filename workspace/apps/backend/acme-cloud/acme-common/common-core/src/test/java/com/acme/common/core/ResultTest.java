package com.acme.common.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Result 契约级语义测试（AC-005、架构设计 6.2）：ok 携带数据、fail 携带错误码。
 */
class ResultTest {

    @Test
    void okCarriesData() {
        Result<String> result = Result.ok("data");
        assertTrue(result.getSuccess());
        assertEquals("data", result.getData());
    }

    @Test
    void failCarriesErrorCode() {
        Result<String> result = Result.fail(TestErrorCodes.DEMO);
        assertFalse(result.getSuccess());
        assertEquals(TestErrorCodes.DEMO.code(), result.getCode());
        assertEquals(TestErrorCodes.DEMO.defaultMessage(), result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void failWithoutErrorCodeFailsExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> Result.fail(null));
    }

    @Test
    void failWithMessageOverridesDefault() {
        Result<String> result = Result.fail(TestErrorCodes.DEMO, "自定义提示");
        assertFalse(result.getSuccess());
        assertEquals("自定义提示", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void failWithDetailCarriesData() {
        FieldErrorDetail detail = new FieldErrorDetail("name", "不能为空");
        Result<java.util.List<FieldErrorDetail>> result =
                Result.fail(TestErrorCodes.DEMO, "校验失败", java.util.List.of(detail));
        assertFalse(result.getSuccess());
        assertEquals("校验失败", result.getMessage());
        assertEquals(java.util.List.of(detail), result.getData());
    }

    @Test
    void failWithDetailAndNullDataKeepsBehavior() {
        Result<String> result = Result.fail(TestErrorCodes.DEMO, null, null);
        assertEquals(TestErrorCodes.DEMO.defaultMessage(), result.getMessage());
        assertNull(result.getData());
    }
}
