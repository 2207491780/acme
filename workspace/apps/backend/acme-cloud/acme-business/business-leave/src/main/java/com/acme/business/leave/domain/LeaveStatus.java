package com.acme.business.leave.domain;

/**
 * 请假单状态（REQ-20261122-233425 确认⑤终稿）：APPLYING（已建单）→ APPROVING（流程实例启动）
 * → FILED（HR 备案 ServiceTask 委托落库）→ DONE/REJECTED（INSTANCE_COMPLETED 事件按 businessKey
 * 回写终态——事件流第一个真实消费先例）。
 */
public enum LeaveStatus {

    /** 已建单（申请端点落库初值）。 */
    APPLYING,

    /** 审批中（流程实例启动成功）。 */
    APPROVING,

    /** 已备案（HR 备案 ServiceTask handler 落库）。 */
    FILED,

    /** 已完成（INSTANCE_COMPLETED 回写终态——审批通过且备案链走通）。 */
    DONE,

    /** 已拒绝（INSTANCE_COMPLETED 回写终态——实例终结未走备案链）。 */
    REJECTED
}
