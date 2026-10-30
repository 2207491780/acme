package com.acme.framework.tx.support;

import java.time.Duration;

/**
 * 强一致使用约束说明面（FR-023、BR-014/015、Backend.md 10.2.4）：
 * 强一致分布式事务仅限「分支事务数量少、执行时间短」且经架构评审的场景；
 * 跨服务一致性默认最终一致（10.2.3）；事务内不得远程调用（M-05）不因本模块放宽；
 * 事务超时预算应小于上游读超时（10.4 衔接，架构设计 13.2）。
 */
public final class TxConstraints {

    private TxConstraints() {
    }

    /**
     * 校验事务超时预算（FR-022、10.4）：事务超时须为正且小于上游读超时（提供时）。
     *
     * @throws IllegalArgumentException 超时预算非法
     */
    public static void requireTimeoutBudget(Duration txTimeout, Duration upstreamReadTimeout) {
        if (txTimeout == null || txTimeout.isNegative() || txTimeout.isZero()) {
            throw new IllegalArgumentException("事务超时预算须为正（FR-022）");
        }
        if (upstreamReadTimeout != null && !upstreamReadTimeout.isZero() && !upstreamReadTimeout.isNegative()
                && txTimeout.compareTo(upstreamReadTimeout) >= 0) {
            throw new IllegalArgumentException("事务超时预算应小于上游读超时（10.4）");
        }
    }
}
