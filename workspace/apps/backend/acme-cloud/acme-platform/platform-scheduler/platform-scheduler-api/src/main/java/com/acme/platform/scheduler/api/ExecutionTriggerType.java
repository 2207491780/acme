package com.acme.platform.scheduler.api;

/**
 * 执行记录触发来源（契约确认③：sch_job_execution.trigger_type 取值域）。
 */
public enum ExecutionTriggerType {

    /** 调度内核周期触发（受部分唯一索引 uk(def_id, fire_time) 幂等约束）。 */
    SCHEDULE,

    /** 管理面手动触发（POST /admin/v1/scheduler/jobs/{id}/trigger）。 */
    MANUAL,

    /** 失败重试链（attempt+1；不受 SCHEDULE 唯一索引约束）。 */
    RETRY,

    /** 业务事件触发（JOB-5 预留取值，本 REQ 不产生）。 */
    EVENT
}
