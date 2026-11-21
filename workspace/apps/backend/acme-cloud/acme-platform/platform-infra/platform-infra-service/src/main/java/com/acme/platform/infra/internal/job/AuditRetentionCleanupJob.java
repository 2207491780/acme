package com.acme.platform.infra.internal.job;

import com.acme.framework.job.support.JobHandler;
import com.acme.framework.job.support.TaskTrigger;
import com.acme.platform.infra.application.AuditRetentionCleanupUseCase;
import com.acme.platform.infra.application.JobExecutionRecorder;
import com.acme.platform.infra.domain.JobExecution;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计事件保留期清理任务（REQ-20261117-061017 {@code audit-retention-cleanup}，确认②）：
 * 调度入口（cron 可配，缺省每日 04:40）与手动触发同径互斥（FR-028 两径一致，经 {@link TaskTrigger}
 * 代理链走 {@code @JobHandler} 切面 + ShedLock）；执行记录锁内创建（未获锁不产生 sys_job_execution 行），
 * 终态回填成功/失败。
 * REQ-20261121-200724 确认⑦：本地 {@code @AcmeTask} 迁移为 {@code @JobHandler}（可被调度中心
 * 下发执行；下发路径 triggerType=SCHEDULER_DISPATCH，SDK 上报落 sch_job_execution，调度侧写），
 * 本地 @Scheduled 兜底行保留（executor.enabled=false 或降级时生效；双跑幂等由保留期语义+互斥锁保证）。
 */
@Component
public class AuditRetentionCleanupJob {

    /** 任务标识（集群内唯一；注册表 key 与锁 key 段）。 */
    public static final String JOB_KEY = "audit-retention-cleanup";

    private final TaskTrigger taskTrigger;
    private final JobExecutionRecorder recorder;
    private final AuditRetentionCleanupUseCase cleanupUseCase;

    public AuditRetentionCleanupJob(TaskTrigger taskTrigger, JobExecutionRecorder recorder,
                                    AuditRetentionCleanupUseCase cleanupUseCase) {
        this.taskTrigger = taskTrigger;
        this.recorder = recorder;
        this.cleanupUseCase = cleanupUseCase;
    }

    /**
     * 任务体（互斥锁内执行）：起执行记录 → 清理软删超期审计事件 → 终态回填。
     *
     * @param triggerType MANUAL/SCHEDULED/SCHEDULER_DISPATCH（触发来源，确认⑦ 归一）
     * @return 执行记录 id（手动触发返回值）
     */
    @JobHandler(jobKey = JOB_KEY,
            description = "审计保留期清理：硬删软删超期 acme_op_audit（acme.audit.retention-days）")
    public Long execute(String triggerType) {
        Long executionId = recorder.start(JOB_KEY, triggerType);
        try {
            cleanupUseCase.cleanupExpired();
            recorder.finish(executionId, JobExecution.STATUS_SUCCESS, null);
            return executionId;
        } catch (RuntimeException ex) {
            recorder.finish(executionId, JobExecution.STATUS_FAILED,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    /** 调度入口（与手动触发同径：经 TaskTrigger 走代理链互斥执行；锁被持时按触发语义显式抛出）。 */
    @Scheduled(cron = "${acme.audit.retention-cron:0 40 4 * * *}")
    public void scheduledRun() {
        taskTrigger.trigger(JOB_KEY, JobExecution.TRIGGER_SCHEDULED);
    }
}
