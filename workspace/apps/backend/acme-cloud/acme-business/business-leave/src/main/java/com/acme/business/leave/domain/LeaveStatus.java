package com.acme.business.leave.domain;

/**
 * 请假单状态（REQ-20261122-233425 确认⑤终稿；REQ-20261128-092210 P1-03/P1-04 语义对齐）：
 * APPLYING（已建单，流程启动后置 afterCommit/补偿重试）→ APPROVING（审批办结后、HR 备案前）
 * → FILED（HR 备案 ServiceTask 委托落库）→ DONE/REJECTED（INSTANCE_COMPLETED 事件按 businessKey
 * 回写终态——事件流第一个真实消费先例）。
 */
public enum LeaveStatus {

    /** 已建单（申请端点同事务落库初值；流程启动后置补偿，成功异步回写 instance_id）。 */
    APPLYING,

    /** 审批中（审批办结后、HR 备案前状态域——TASK_COMPLETED 事件自消费或备案下发权威推进第一步）。 */
    APPROVING,

    /** 已备案（HR 备案 ServiceTask handler 落库）。 */
    FILED,

    /** 已完成（INSTANCE_COMPLETED 回写终态——审批通过且备案链走通）。 */
    DONE,

    /** 已拒绝（INSTANCE_COMPLETED 回写终态——实例终结未走备案链）。 */
    REJECTED
}
