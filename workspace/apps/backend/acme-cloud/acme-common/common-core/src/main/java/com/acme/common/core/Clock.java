package com.acme.common.core;

import com.acme.common.core.internal.SystemClock;

import java.time.Instant;

/**
 * 时钟抽象（FR-019）：统一时间获取口径，测试可注入可控时间。无 IO。
 *
 * @implNote 暂缓 DP-007：可注入时间源的完整实现形态待定稿。
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public interface Clock {

    /** 当前时间点（FR-019）。 */
    Instant now();

    /**
     * 系统时钟（FR-019）。
     *
     * @implNote 暂缓 DP-007：完整实现形态待定稿。
     */
    static Clock system() {
        return SystemClock.INSTANCE;
    }
}
