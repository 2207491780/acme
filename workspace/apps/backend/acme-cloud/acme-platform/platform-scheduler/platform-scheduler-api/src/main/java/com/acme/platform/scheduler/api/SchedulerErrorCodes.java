package com.acme.platform.scheduler.api;

/**
 * 调度中心域错误码文本常量（Backend.md 10.5.2 三段式，serviceId=platform-scheduler、模块段自管）：
 * 供跨服务消费方按码判定；完整枚举（含 HTTP 映射）归 platform-scheduler-service SchedulerError，
 * 两处取值一致由实现说明与单测锁定。
 */
public final class SchedulerErrorCodes {

    // ---- 01 段：任务定义域 ----

    /** job_key 已存在（创建 / 一次性任务注册撞既有键，409）。 */
    public static final String SCHEDULER_JOB_KEY_DUPLICATE = "platform-scheduler-01-0001";

    /** 任务定义不存在（更新 / 启停 / 删除 / 手动触发对不存在 id，404）。 */
    public static final String SCHEDULER_JOB_NOT_FOUND = "platform-scheduler-01-0002";

    /** job_key 创建后不可修改（PUT 变更被拒绝，409；契约确认②终稿）。 */
    public static final String SCHEDULER_JOB_KEY_IMMUTABLE = "platform-scheduler-01-0003";

    /** 触发参数非法（CRON 语法错 / FIXED_RATE 非正数 / ONCE 缺 onceFireAt，400）。 */
    public static final String SCHEDULER_TRIGGER_INVALID = "platform-scheduler-01-0004";

    /** 重试策略非法（maxAttempts 负数 / initialIntervalMs 非正数，400）。 */
    public static final String SCHEDULER_RETRY_POLICY_INVALID = "platform-scheduler-01-0005";

    // ---- 02 段：执行历史域 ----

    /** 执行记录不存在（结果回调对未知 executionId，404）。 */
    public static final String SCHEDULER_EXECUTION_NOT_FOUND = "platform-scheduler-02-0001";

    /** 执行状态跃迁冲突（结果回调 CAS 不命中：PENDING/DISPATCHED 不可直接 SUCCESS、终态不可再推进，409）。 */
    public static final String SCHEDULER_EXECUTION_STATE_CONFLICT = "platform-scheduler-02-0002";

    private SchedulerErrorCodes() {
    }
}
