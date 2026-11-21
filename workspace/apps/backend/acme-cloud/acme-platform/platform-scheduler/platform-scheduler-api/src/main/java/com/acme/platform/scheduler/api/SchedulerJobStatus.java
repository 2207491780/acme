package com.acme.platform.scheduler.api;

/**
 * 任务定义启停状态（契约确认③：创建默认 DISABLED，显式启用；对齐 EnableStatus wire 名）。
 */
public enum SchedulerJobStatus {

    /** 停用（创建默认值：不参与调度触发）。 */
    DISABLED,

    /** 启用（参与调度触发）。 */
    ENABLED
}
