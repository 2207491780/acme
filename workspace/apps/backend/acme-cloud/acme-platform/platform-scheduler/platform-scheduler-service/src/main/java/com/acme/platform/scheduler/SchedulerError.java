package com.acme.platform.scheduler;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.scheduler.api.SchedulerErrorCodes;

/**
 * 调度中心域错误码枚举（10.5.2 三段式，serviceId=platform-scheduler；架构设计 8.1 分段表）。
 * 码值与 platform-scheduler-api SchedulerErrorCodes 常量一一对应（跨服务判定取常量、本服务抛枚举）。
 */
public enum SchedulerError implements ErrorCode, HttpStatusAware {

    // ---- 01 段：任务定义域 ----

    /** job_key 已存在（创建 / 一次性任务注册撞既有键）。 */
    SCHEDULER_JOB_KEY_DUPLICATE(SchedulerErrorCodes.SCHEDULER_JOB_KEY_DUPLICATE, "任务键已存在", 409),

    /** 任务定义不存在（更新 / 启停 / 删除 / 手动触发对不存在 id）。 */
    SCHEDULER_JOB_NOT_FOUND(SchedulerErrorCodes.SCHEDULER_JOB_NOT_FOUND, "任务定义不存在", 404),

    /** job_key 创建后不可修改（PUT 变更被拒绝；契约确认②终稿）。 */
    SCHEDULER_JOB_KEY_IMMUTABLE(SchedulerErrorCodes.SCHEDULER_JOB_KEY_IMMUTABLE, "任务键创建后不可修改", 409),

    /** 触发参数非法（CRON 语法错 / FIXED_RATE 非正数 / ONCE 缺 onceFireAt；Spring CronExpression 校验承载）。 */
    SCHEDULER_TRIGGER_INVALID(SchedulerErrorCodes.SCHEDULER_TRIGGER_INVALID, "触发参数非法", 400),

    /** 重试策略非法（maxAttempts 负数 / initialIntervalMs 非正数）。 */
    SCHEDULER_RETRY_POLICY_INVALID(SchedulerErrorCodes.SCHEDULER_RETRY_POLICY_INVALID, "重试策略非法", 400),

    // ---- 02 段：执行历史域 ----

    /** 执行记录不存在（结果回调对未知 executionId）。 */
    SCHEDULER_EXECUTION_NOT_FOUND(SchedulerErrorCodes.SCHEDULER_EXECUTION_NOT_FOUND, "执行记录不存在", 404),

    /** 执行状态跃迁冲突（结果回调 CAS 不命中：非法跃迁或终态再推进）。 */
    SCHEDULER_EXECUTION_STATE_CONFLICT(SchedulerErrorCodes.SCHEDULER_EXECUTION_STATE_CONFLICT,
            "执行状态跃迁冲突", 409);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    SchedulerError(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
