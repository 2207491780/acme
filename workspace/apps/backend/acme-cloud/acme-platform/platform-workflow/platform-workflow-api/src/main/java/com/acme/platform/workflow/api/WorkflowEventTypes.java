package com.acme.platform.workflow.api;

/**
 * 流程事件类型常量（REQ-20261122-233425 确认④；跨服务消费方判定面——WorkflowEventEnvelope.
 * eventType 取值域。引擎侧权威常量在 platform-workflow-service WorkflowEvent，取值一致由实现
 * 说明与单测锁定，同 WorkflowErrorCodes 口径）。
 */
public final class WorkflowEventTypes {

    /** 实例启动（StartInstanceCommand 落库登记）。 */
    public static final String INSTANCE_STARTED = "INSTANCE_STARTED";

    /** 实例正常终结（EndEvent 到达——业务终态回写锚点）。 */
    public static final String INSTANCE_COMPLETED = "INSTANCE_COMPLETED";

    /** 实例终止（terminate 撤回/管理终止）。 */
    public static final String INSTANCE_TERMINATED = "INSTANCE_TERMINATED";

    /** 实例挂起（NO_ASSIGNEE_RESOLVED / SERVICE_TASK_FAILED / 运维挂起）。 */
    public static final String INSTANCE_SUSPENDED = "INSTANCE_SUSPENDED";

    /** 实例恢复。 */
    public static final String INSTANCE_RESUMED = "INSTANCE_RESUMED";

    /** 任务创建（待办中心来源面）。 */
    public static final String TASK_CREATED = "TASK_CREATED";

    /** 任务办结。 */
    public static final String TASK_COMPLETED = "TASK_COMPLETED";

    /** 任务驳回（BPM-5 增补埋点：业务侧驳回终态回写锚点——reject 不产生 TASK_COMPLETED）。 */
    public static final String TASK_REJECTED = "TASK_REJECTED";

    /** 定时边界触发。 */
    public static final String TIMER_FIRED = "TIMER_FIRED";

    /** ServiceTask 委托失败（重试耗尽挂起面）。 */
    public static final String SERVICE_TASK_FAILED = "SERVICE_TASK_FAILED";

    private WorkflowEventTypes() {
    }
}
