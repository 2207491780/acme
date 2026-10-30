package com.acme.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 金额辅助测试（FR-006；DP-005 定稿）：四则运算、规整与显式失败口径。
 */
class AmountMathTest {

    @Test
    void arithmeticOperations() {
        assertEquals(new BigDecimal("3.30"), AmountMath.add(new BigDecimal("1.10"), new BigDecimal("2.20")));
        assertEquals(new BigDecimal("-1.10"), AmountMath.subtract(new BigDecimal("1.10"), new BigDecimal("2.20")));
        // 乘法保留全精度：标度 = 两操作数标度之和
        assertEquals(new BigDecimal("0.2420"), AmountMath.multiply(new BigDecimal("1.10"), new BigDecimal("0.22")));
        assertEquals(new BigDecimal("0.33"),
                AmountMath.divide(new BigDecimal("1.10"), new BigDecimal("3.30"), 2, RoundingMode.HALF_UP));
    }

    @Test
    void divideByZeroFailsExplicitly() {
        assertThrows(IllegalArgumentException.class,
                () -> AmountMath.divide(BigDecimal.ONE, BigDecimal.ZERO, 2, RoundingMode.HALF_UP));
    }

    @Test
    void scaleNormalizesWithRoundingMode() {
        assertEquals(new BigDecimal("1.11"), AmountMath.scale(new BigDecimal("1.105"), 2, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> AmountMath.scale(BigDecimal.ONE, -1, RoundingMode.HALF_UP));
    }

    @Test
    void percentOfTreatsRateAsRatio() {
        assertEquals(new BigDecimal("12.50"), AmountMath.percentOf(new BigDecimal("125.0"), new BigDecimal("0.1")));
    }

    @Test
    void nullArgumentsFailExplicitly() {
        assertThrows(NullPointerException.class, () -> AmountMath.add(null, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> AmountMath.percentOf(BigDecimal.ONE, null));
        assertThrows(NullPointerException.class,
                () -> AmountMath.divide(BigDecimal.ONE, BigDecimal.ONE, 2, null));
    }
}
