package com.acme.platform.scheduler.api;

import java.util.Set;

/**
 * 执行记录状态机（契约确认③④：PENDING → DISPATCHED → RUNNING → 终态 SUCCESS/FAILED/TIMEOUT/CANCELLED）。
 * 状态 CAS 口径（确认④终稿）：
 * <ul>
 *   <li>PENDING / DISPATCHED 只可推进到 RUNNING / FAILED / CANCELLED（DISPATCHED→RUNNING=执行器接单；
 *       FAILED 含下发失败 NO_EXECUTOR / DISPATCH_TIMEOUT 等内部口径与回调失败上报）；</li>
 *   <li>PENDING / DISPATCHED 不可直接 SUCCESS（非法跃迁拒绝，SCHEDULER_EXECUTION_STATE_CONFLICT）；</li>
 *   <li>RUNNING 只可推进到终态（SUCCESS / FAILED / TIMEOUT / CANCELLED）；</li>
 *   <li>终态（SUCCESS / FAILED / TIMEOUT / CANCELLED）不可再推进。</li>
 * </ul>
 */
public enum ExecutionState {

    /** 已创建待下发（调度内核 / 重试链 / 手动触发落库初始态）。 */
    PENDING,

    /** 已下发（下发请求在途，接单确认前）。 */
    DISPATCHED,

    /** 执行器已接单执行中（接单确认或回调开跑）。 */
    RUNNING,

    /** 执行成功（终态）。 */
    SUCCESS,

    /** 执行失败（终态；重试链未耗尽时由内核另起 RETRY execution）。 */
    FAILED,

    /** 执行超时（终态；超时巡检判定 RUNNING 超 timeout_ms）。 */
    TIMEOUT,

    /** 已取消（终态；管理面主动取消）。 */
    CANCELLED;

    /** 非终态集合（同 job 串行门：存在任一非终态 execution 则跳过下一次 SCHEDULE 触发）。 */
    public static final Set<ExecutionState> NON_TERMINAL = Set.of(PENDING, DISPATCHED, RUNNING);

    /** 是否终态。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMEOUT || this == CANCELLED;
    }

    /**
     * 结果回调 / 内部推进的合法跃迁判定（确认④ CAS 终稿）。
     *
     * @param target 目标状态
     * @return true=允许跃迁
     */
    public boolean canAdvanceTo(ExecutionState target) {
        if (this.isTerminal()) {
            return false;
        }
        if (this == RUNNING) {
            // RUNNING：仅终态可推进（TIMEOUT 归超时巡检、SUCCESS/FAILED 归结果回调、CANCELLED 归管理面）
            return target == SUCCESS || target == FAILED || target == TIMEOUT || target == CANCELLED;
        }
        // PENDING/DISPATCHED：接单(RUNNING)、失败/取消可推进；SUCCESS 非法（不可跳过执行直接成功）
        return target == RUNNING || target == FAILED || target == CANCELLED;
    }
}
