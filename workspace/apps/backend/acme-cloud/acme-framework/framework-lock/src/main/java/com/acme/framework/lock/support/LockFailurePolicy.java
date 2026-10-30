package com.acme.framework.lock.support;

/**
 * 锁获取失败策略（FR-012/AC-012，取舍 #8 定稿）：绝不无锁静默并发执行（FR-009 红线）。
 */
public enum LockFailurePolicy {

    /** 快速失败（默认）：抛 LOCK_ACQUIRE_FAILED。 */
    FAIL_FAST,

    /** 有限等待（waitMillis 上限）后仍失败按失败处理，不无限等待。 */
    WAIT,

    /** 跳过执行（仅 void 方法；非 void 声明即显式失败）。 */
    SKIP
}
