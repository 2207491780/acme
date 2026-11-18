package com.acme.platform.infra.internal.job;

import com.acme.framework.job.support.AcmeTask;
import com.acme.framework.job.support.TaskTrigger;
import com.acme.platform.infra.application.FileRetentionCleanupUseCase;
import com.acme.platform.infra.application.JobExecutionRecorder;
import com.acme.platform.infra.domain.JobExecution;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件保留期清理任务（REQ-20261114-071607 示例任务 {@code file-retention-cleanup}）：
 * 调度入口（cron 可配，缺省每日 03:00）与手动触发同径互斥（FR-028 两径一致，经
 * {@link TaskTrigger} 代理链走 {@code @AcmeTask} 切面 + ShedLock）；执行记录锁内创建
 * （未获锁不产生 sys_job_execution 行），终态回填成功/失败。
 */
@Component
public class FileRetentionCleanupJob {

    /** 任务标识（集群内唯一；注册表 key 与锁 key 段）。 */
    public static final String JOB_KEY = "file-retention-cleanup";

    private final TaskTrigger taskTrigger;
    private final JobExecutionRecorder recorder;
    private final FileRetentionCleanupUseCase cleanupUseCase;

    public FileRetentionCleanupJob(TaskTrigger taskTrigger, JobExecutionRecorder recorder,
                                   FileRetentionCleanupUseCase cleanupUseCase) {
        this.taskTrigger = taskTrigger;
        this.recorder = recorder;
        this.cleanupUseCase = cleanupUseCase;
    }

    /**
     * 任务体（互斥锁内执行）：起执行记录 → 清理软删超期文件 → 终态回填。
     *
     * @param triggerType MANUAL/SCHEDULED（触发来源，两径同一体）
     * @return 执行记录 id（手动触发返回值）
     */
    @AcmeTask(taskId = JOB_KEY)
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
    @Scheduled(cron = "${acme.storage.upload.retention-cron:0 0 3 * * *}")
    public void scheduledRun() {
        taskTrigger.trigger(JOB_KEY, JobExecution.TRIGGER_SCHEDULED);
    }
}
