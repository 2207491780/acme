package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.workflow.domain.WfTimerJob;

import java.time.Instant;
import java.util.List;

/** 定时任务仓储（确认⑥：PENDING/FIRED/CANCELLED 状态面 + 本地兜底扫描）。 */
public interface WfTimerJobRepository extends FlexRepository<WfTimerJob> {

    /**
     * 到期待触发（本地 ScheduledExecutorService 兜底扫描：PENDING 且 due_at <= now）。
     * 平台级巡检旁路（12.4.2 显式声明）：兜底线程无租户上下文，扫描面读全租户、
     * 触发前以行租户绑定命令线程。
     */
    List<WfTimerJob> findPendingDue(Instant now);

    /** 节点维度待触发（任务完成/驳回离开/终止时注销）。 */
    List<WfTimerJob> findPendingByInstanceIdAndNodeId(Long instanceId, String nodeId);

    /** 实例维度待触发（实例终止注销全集）。 */
    List<WfTimerJob> findPendingByInstanceId(Long instanceId);

    /** 按调度任务键定位（fire 回调幂等锚点 uk(scheduler_job_key)）。 */
    java.util.Optional<WfTimerJob> findBySchedulerJobKey(String schedulerJobKey);

    /**
     * 原子 CAS：PENDING→FIRED 单向迁移（BPM-1 round-2 D4——fire 端点与本地兜底双通道统一的
     * 权威抢占面：UPDATE ... WHERE id=? AND status='PENDING'，返回 1=抢占成功（允许中断），
     * 0=已被并发通道消费（静默跳过）——杜绝双通道二次中断）。
     */
    int casFire(Long id);

    /**
     * 按 PK 全局直读（fire 回调/兜底触发旁路声明——12.4.2；fire 通道可能无租户上下文：
     * 调度中心下发主体 tenantId=null / 本地兜底线程无上下文，行租户为权威）。
     */
    java.util.Optional<WfTimerJob> findByIdGlobal(Long id);
}
