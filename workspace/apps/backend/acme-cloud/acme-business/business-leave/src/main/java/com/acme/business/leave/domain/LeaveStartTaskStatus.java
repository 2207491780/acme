package com.acme.business.leave.domain;

/**
 * 流程启动补偿任务状态（REQ-20261128-092210 确认②③）：PENDING(attempts=0 初始)→（乐观锁认领
 * CAS→RUNNING）→成功 DONE / 失败 PENDING(attempts≥1, 指数退避)；RUNNING 超租约回 PENDING（自愈）；
 * attempts 达尝试上限 → EXHAUSTED（结构化 warn 留痕，告警面归 P1-11 批）。
 */
public enum LeaveStartTaskStatus {

    /** 待执行（attempts=0 初始；失败回置，携 next_retry_at 指数退避）。 */
    PENDING,

    /** 执行中（乐观锁认领 CAS 落定——恰一执行者；超租约由扫描回收回 PENDING）。 */
    RUNNING,

    /** 已完成（leave_request.instance_id 回写成功）。 */
    DONE,

    /** 重试耗尽（达尝试上限，leave 行保持 APPLYING 非死态）。 */
    EXHAUSTED
}
