package com.acme.common.core;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 金额值类型（FR-018；Q-003 结论：最小骨架，仅金额数值承载）。
 * 纯计算函数归 common-util.AmountMath，互不引用（架构设计 11）。
 * 值相等按数值语义（compareTo==0，忽略标度差异），hashCode 与之一致（DP-007 定稿）。
 *
 * <p>非法输入口径（DP-007 定稿）：仅拒绝 null（NullPointerException）；
 * 符号与标度语义归调用方（退款等业务场景允许负值），四则运算归 AmountMath。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class Money {

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    /** 金额数值承载（FR-018）；null 显式失败（DP-007 定稿）。 */
    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount 不能为空");
        return new Money(amount);
    }

    public BigDecimal amount() {
        return amount;
    }

    /** 值相等（DP-007 定稿）：按数值语义，忽略标度差异（1.0 与 1.00 相等）。 */
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Money other && amount.compareTo(other.amount) == 0);
    }

    /** 与 {@link #equals} 一致：去尾零后取哈希（compareTo 相等 ⇒ 哈希相等）。 */
    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
