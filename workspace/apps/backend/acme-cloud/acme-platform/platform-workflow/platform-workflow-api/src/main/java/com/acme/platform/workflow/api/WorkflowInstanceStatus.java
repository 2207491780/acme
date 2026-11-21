package com.acme.platform.workflow.api;

/**
 * 流程实例状态（契约确认②终稿）：RUNNING 运行 / SUSPENDED 挂起（运维挂起、ServiceTask
 * 委托失败 SERVICE_TASK_FAILED、无候选人 NO_ASSIGNEE_RESOLVED）/ COMPLETED 正常终止 /
 * TERMINATED 发起人撤回或管理终止。
 */
public enum WorkflowInstanceStatus {

    RUNNING,
    SUSPENDED,
    COMPLETED,
    TERMINATED
}
