package com.acme.common.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Money 值语义测试（FR-018、DP-007 本次定稿）：值相等忽略标度、非法输入显式失败。
 */
class MoneyTest {

    @Test
    void equalsIgnoresScale() {
        assertEquals(Money.of(new BigDecimal("1.0")), Money.of(new BigDecimal("1.00")));
        assertEquals(Money.of(new BigDecimal("1.0")).hashCode(),
                Money.of(new BigDecimal("1.00")).hashCode());
        assertNotEquals(Money.of(new BigDecimal("1.0")), Money.of(new BigDecimal("1.1")));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(NullPointerException.class, () -> Money.of(null));
    }

    @Test
    void toStringKeepsPlainForm() {
        assertEquals("12.30", Money.of(new BigDecimal("12.30")).toString());
    }
}
