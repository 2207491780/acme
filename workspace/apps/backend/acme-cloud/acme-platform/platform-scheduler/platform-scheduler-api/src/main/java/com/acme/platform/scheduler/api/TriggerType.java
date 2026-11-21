package com.acme.platform.scheduler.api;

/**
 * 任务触发类型（契约确认③：sch_job_definition.trigger_type 取值域）。
 */
public enum TriggerType {

    /** CRON 表达式（Spring {@code CronExpression} 6 位域解析）。 */
    CRON,

    /** 固定频率（fixed_rate_ms 间隔，触发后即时计算下一轮）。 */
    FIXED_RATE,

    /** 一次性（once_fire_at 指定时刻，触发后不再调度；BPM 一次性任务开通位）。 */
    ONCE
}
