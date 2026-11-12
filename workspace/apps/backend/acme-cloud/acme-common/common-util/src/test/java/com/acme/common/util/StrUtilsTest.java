package com.acme.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StrUtils 契约级语义测试（AC-005、架构设计 6.2）：isBlank 判定语义（null/空白）。
 */
class StrUtilsTest {

    @Test
    void isEmptyTreatsNullAsEmpty() {
        assertTrue(StrUtils.isEmpty(null));
        assertTrue(StrUtils.isEmpty(""));
        assertFalse(StrUtils.isEmpty(" "));
        assertFalse(StrUtils.isEmpty("a"));
    }

    @Test
    void isBlankTreatsNullAndWhitespaceAsBlank() {
        assertTrue(StrUtils.isBlank(null));
        assertTrue(StrUtils.isBlank(""));
        assertTrue(StrUtils.isBlank("  "));
        assertFalse(StrUtils.isBlank("a"));
    }

    @Test
    void truncateCutsToMaxLength() {
        assertEquals("ab", StrUtils.truncate("abc", 2));
        assertEquals("abc", StrUtils.truncate("abc", 5));
    }
}
