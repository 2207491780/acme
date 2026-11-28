package com.acme.business.leave.infrastructure.repository;

import com.acme.business.leave.domain.LeaveStartTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 流程启动补偿任务仓储契约（REQ-20261128-092210 确认②；认领/扫描查询面——乐观锁/审计/软删由
 * FlexRepositorySupport 承载；认领 CAS 依赖按实体更新的乐观锁条件，0 行更新=STALE_WRITE）。
 */
public interface LeaveStartTaskRepository {

    /** 保存（插入/乐观锁更新；0 行更新=并发冲突 STALE_WRITE——认领 CAS 的裁决机制）。 */
    LeaveStartTask save(LeaveStartTask task);

    /** 主键装载（认领/回收前重读最新行与版本）。 */
    Optional<LeaveStartTask> findById(Long id);

    /** 按请假单装载（一单一任务，uk_leave_start_task_leave）。 */
    Optional<LeaveStartTask> findByLeaveId(Long leaveId);

    /**
     * 扫描到期可执行任务（跨租户捞取——扫描线程不绑租户上下文，flex tenantId 列仅在上下文在场时
     * 过滤；三腿互斥不重叠）：PENDING 退避到期 / PENDING 零尝试超 grace（afterCommit 崩溃防丢失）/
     * RUNNING 超租约（执行器崩溃回收，回 PENDING 重投）。
     */
    List<LeaveStartTask> findRetryable(Instant now, long graceMs, long leaseMs);
}
