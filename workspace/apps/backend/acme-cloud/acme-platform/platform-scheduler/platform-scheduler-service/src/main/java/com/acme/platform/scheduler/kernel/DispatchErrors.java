package com.acme.platform.scheduler.kernel;

/**
 * 下发失败口径常量（确认⑤终稿；落 sch_job_execution.error 列，非 HTTP 错误码——
 * 失败语义三态分离：无执行器 / 接单超时 / 接单拒绝，其余连接类失败归 DISPATCH_FAILED）。
 */
public final class DispatchErrors {

    /** 无在线执行器（确认⑤：走重试链）。 */
    public static final String NO_EXECUTOR = "NO_EXECUTOR";

    /** 接单超时（确认⑤：10s 未 200 → 走重试链）。 */
    public static final String DISPATCH_TIMEOUT = "DISPATCH_TIMEOUT";

    /** 执行器拒绝接单（accepted=false，执行池满等 SDK 语义 → 走重试链）。 */
    public static final String DISPATCH_REJECTED = "DISPATCH_REJECTED";

    /** 其余下发失败（连接拒绝 / 5xx / 提交下发线程池失败等 → 走重试链）。 */
    public static final String DISPATCH_FAILED = "DISPATCH_FAILED";

    /** 超时巡检命中（RUNNING 超 timeout_ms → TIMEOUT）。 */
    public static final String EXECUTION_TIMEOUT = "EXECUTION_TIMEOUT";

    private DispatchErrors() {
    }
}
