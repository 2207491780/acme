package com.acme.framework.job.support;

/**
 * 任务触发来源词表（REQ-20261121-200724 确认⑦ triggerType 归一）：本地路径沿用既有取值
 * （MANUAL/SCHEDULED），调度中心下发执行经执行器 SDK 恒传 {@code SCHEDULER_DISPATCH}。
 */
public final class JobExecutionTriggers {

    /** 调度中心下发执行（执行器 SDK 接单路径；确认⑦ 归一取值）。 */
    public static final String SCHEDULER_DISPATCH = "SCHEDULER_DISPATCH";

    private JobExecutionTriggers() {
    }
}
