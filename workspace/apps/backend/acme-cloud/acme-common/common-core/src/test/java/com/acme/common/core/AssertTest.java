package com.acme.common.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Assert 契约测试（FR-017）：断言失败抛 BizException 并携带指定错误码。
 */
class AssertTest {

    @Test
    void passThroughWhenConditionHolds() {
        assertDoesNotThrow(() -> Assert.isTrue(true, TestErrorCodes.DEMO));
        assertDoesNotThrow(() -> Assert.isFalse(false, TestErrorCodes.DEMO));
        assertDoesNotThrow(() -> Assert.notNull("v", TestErrorCodes.DEMO));
    }

    @Test
    void failureCarriesErrorCode() {
        BizException e = assertThrows(BizException.class,
                () -> Assert.isTrue(false, TestErrorCodes.DEMO));
        assertEquals(TestErrorCodes.DEMO, e.errorCode());
        assertEquals(TestErrorCodes.DEMO.defaultMessage(), e.getMessage());
    }

    @Test
    void notNullRejectsNull() {
        BizException e = assertThrows(BizException.class,
                () -> Assert.notNull(null, TestErrorCodes.DEMO));
        assertEquals(TestErrorCodes.DEMO, e.errorCode());
    }

    @Test
    void isFalseRejectsTrue() {
        assertThrows(BizException.class, () -> Assert.isFalse(true, TestErrorCodes.DEMO));
    }

    @Test
    void notBlankRejectsNullAndWhitespace() {
        assertDoesNotThrow(() -> Assert.notBlank("v", TestErrorCodes.DEMO));
        assertThrows(BizException.class, () -> Assert.notBlank(null, TestErrorCodes.DEMO));
        assertThrows(BizException.class, () -> Assert.notBlank("  ", TestErrorCodes.DEMO));
    }

    @Test
    void notEmptyRejectsNullAndEmptyString() {
        assertDoesNotThrow(() -> Assert.notEmpty("v", TestErrorCodes.DEMO));
        assertThrows(BizException.class, () -> Assert.notEmpty((CharSequence) null, TestErrorCodes.DEMO));
        assertThrows(BizException.class, () -> Assert.notEmpty("", TestErrorCodes.DEMO));
    }

    @Test
    void notEmptyRejectsNullAndEmptyCollection() {
        assertDoesNotThrow(() -> Assert.notEmpty(java.util.List.of("v"), TestErrorCodes.DEMO));
        assertThrows(BizException.class,
                () -> Assert.notEmpty((java.util.Collection<?>) null, TestErrorCodes.DEMO));
        assertThrows(BizException.class, () -> Assert.notEmpty(java.util.List.of(), TestErrorCodes.DEMO));
    }

    @Test
    void noNullElementsRejectsNullAndNullElements() {
        assertDoesNotThrow(() -> Assert.noNullElements(java.util.List.of(), TestErrorCodes.DEMO));
        assertDoesNotThrow(() -> Assert.noNullElements(java.util.List.of("a", "b"), TestErrorCodes.DEMO));
        assertThrows(BizException.class,
                () -> Assert.noNullElements(null, TestErrorCodes.DEMO));
        assertThrows(BizException.class,
                () -> Assert.noNullElements(java.util.Arrays.asList("a", null), TestErrorCodes.DEMO));
    }

    @Test
    void stateBehavesLikeIsTrue() {
        assertDoesNotThrow(() -> Assert.state(true, TestErrorCodes.DEMO));
        assertEquals(TestErrorCodes.DEMO,
                assertThrows(BizException.class, () -> Assert.state(false, TestErrorCodes.DEMO)).errorCode());
    }
}
