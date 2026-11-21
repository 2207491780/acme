package com.acme.platform.workflow.api;

/**
 * 任务状态（契约确认②终稿）：CREATED 已生成未认领（固定 assignee 亦先 CREATED）→ CLAIMED
 * 已认领 → COMPLETED 办结 / REJECTED 驳回关闭 / CANCELLED 取消（驳回离开/终止/定时中断）。
 */
public enum WorkflowTaskStatus {

    CREATED,
    CLAIMED,
    COMPLETED,
    REJECTED,
    CANCELLED
}
