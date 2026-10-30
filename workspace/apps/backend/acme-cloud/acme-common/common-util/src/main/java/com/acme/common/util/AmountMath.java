package com.acme.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额辅助（FR-006）。仅对原始数值（{@link BigDecimal}）做纯计算函数（Q-003：util=纯计算函数）；
 * 值类型归 common-core.Money，本类不引用 Money（依赖方向：core → util）。
 *
 * <p>换算/舍入细则（DP-005 定稿）：运算入参为 null → NullPointerException；
 * scale 为负或除数为零 → IllegalArgumentException；除法必须显式给定 scale 与舍入模式
 * （不使用 JVM 默认精度，避免非整除时 ArithmeticException）；乘法保留全精度、
 * 落库/展示前由调用方按币种小数位 {@link #scale} 规整。percentOf 的 rate 为比例小数
 * （如 0.1 表示 10%）。本类不定义币种与舍入模式业务语义。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class AmountMath {

    private AmountMath() {
    }

    /** 加法（FR-006；DP-005 定稿）。 */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        Objects.requireNonNull(a, "a 不能为空");
        Objects.requireNonNull(b, "b 不能为空");
        return a.add(b);
    }

    /** 减法（FR-006；DP-005 定稿）。 */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        Objects.requireNonNull(a, "a 不能为空");
        Objects.requireNonNull(b, "b 不能为空");
        return a.subtract(b);
    }

    /** 乘法（FR-006；DP-005 定稿）：保留全精度，规整由调用方负责。 */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        Objects.requireNonNull(a, "a 不能为空");
        Objects.requireNonNull(b, "b 不能为空");
        return a.multiply(b);
    }

    /** 除法（FR-006；DP-005 定稿）：显式 scale 与舍入模式；除数为零显式失败。 */
    public static BigDecimal divide(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        Objects.requireNonNull(a, "a 不能为空");
        Objects.requireNonNull(b, "b 不能为空");
        Objects.requireNonNull(mode, "mode 不能为空");
        if (scale < 0) {
            throw new IllegalArgumentException("scale 不得为负");
        }
        if (b.signum() == 0) {
            throw new IllegalArgumentException("除数不能为零");
        }
        return a.divide(b, scale, mode);
    }

    /** 按指定小数位与舍入模式规整（FR-006；DP-005 定稿）。 */
    public static BigDecimal scale(BigDecimal value, int scale, RoundingMode mode) {
        Objects.requireNonNull(value, "value 不能为空");
        Objects.requireNonNull(mode, "mode 不能为空");
        if (scale < 0) {
            throw new IllegalArgumentException("scale 不得为负");
        }
        return value.setScale(scale, mode);
    }

    /** 纯计算：base × rate（FR-006）；rate 为比例小数（0.1 = 10%，DP-005 定稿），保留全精度。 */
    public static BigDecimal percentOf(BigDecimal base, BigDecimal rate) {
        Objects.requireNonNull(base, "base 不能为空");
        Objects.requireNonNull(rate, "rate 不能为空");
        return base.multiply(rate);
    }
}
