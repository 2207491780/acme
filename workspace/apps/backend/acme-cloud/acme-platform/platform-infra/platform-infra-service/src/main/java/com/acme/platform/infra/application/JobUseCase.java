package com.acme.platform.infra.application;

import com.acme.common.core.BizException;
import com.acme.framework.job.support.JobError;
import com.acme.framework.job.support.TaskDefinition;
import com.acme.framework.job.support.TaskRegistry;
import com.acme.framework.job.support.TaskTrigger;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.domain.JobExecution;
import com.acme.platform.infra.infrastructure.repository.JobExecutionRepository;
import com.acme.platform.infra.internal.job.FileRetentionCleanupJob;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 任务调度用例（REQ-20261114-071607 §API 任务调度）：注册表查询（key/description/cron/enabled +
 * 最近一次执行摘要）与手动触发（ShedLock 互斥：执行中 JOB_ALREADY_RUNNING 不排队；
 * 未知 jobKey JOB_NOT_FOUND；成功返回执行记录 id）。
 * 手动触发经 framework-job {@link TaskTrigger} 与调度同径互斥（FR-028 两径一致）。
 */
@Service
public class JobUseCase {

    /** 注册表描述目录（TaskDefinition 之外的展示元数据；新增任务同步登记）。 */
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            FileRetentionCleanupJob.JOB_KEY,
            "文件保留期清理：物理删除软删超期 sys_file 及其对象（acme.storage.upload.retention-days）");

    private final TaskRegistry taskRegistry;
    private final TaskTrigger taskTrigger;
    private final JobExecutionRepository jobExecutionRepository;
    private final StorageProperties storageProperties;

    public JobUseCase(TaskRegistry taskRegistry, TaskTrigger taskTrigger,
                      JobExecutionRepository jobExecutionRepository,
                      StorageProperties storageProperties) {
        this.taskRegistry = taskRegistry;
        this.taskTrigger = taskTrigger;
        this.jobExecutionRepository = jobExecutionRepository;
        this.storageProperties = storageProperties;
    }

    /** 注册表列表（key/description/cron/enabled + 最近一次执行摘要，按 key 稳定排序）。 */
    public List<JobView> list() {
        List<JobView> views = new ArrayList<>();
        for (TaskDefinition definition : taskRegistry.all()) {
            views.add(toView(definition));
        }
        views.sort(Comparator.comparing(JobView::key));
        return views;
    }

    /** 手动触发（同步执行；成功返回执行记录 id，AC-13/AC-14）。 */
    public Long run(String jobKey) {
        if (jobKey == null || jobKey.isBlank() || taskRegistry.find(jobKey.trim()).isEmpty()) {
            throw new BizException(InfraError.JOB_NOT_FOUND);
        }
        try {
            Object result = taskTrigger.trigger(jobKey.trim(), JobExecution.TRIGGER_MANUAL);
            if (result instanceof Long executionId) {
                return executionId;
            }
            throw new IllegalStateException("任务未返回执行记录 id: " + jobKey);
        } catch (BizException ex) {
            if (JobError.TASK_RUNNING == ex.errorCode()) {
                throw new BizException(InfraError.JOB_ALREADY_RUNNING);
            }
            throw ex;
        }
    }

    private JobView toView(TaskDefinition definition) {
        String key = definition.taskId();
        boolean catalogued = DESCRIPTIONS.containsKey(key);
        String cron = catalogued ? storageProperties.getUpload().getRetentionCron() : null;
        // enabled = 注册在案且调度 cron 可用（本服务任务集经 @AcmeTask 收集，注册即调度语义）
        boolean enabled = catalogued && cron != null && !cron.isBlank();
        JobView.LastExecution last = jobExecutionRepository.findLatest(key)
                .map(execution -> new JobView.LastExecution(execution.getStatus(),
                        execution.getStartedAt(), execution.getFinishedAt(), execution.getError()))
                .orElse(null);
        return new JobView(key,
                DESCRIPTIONS.getOrDefault(key, definition.accessPoint()),
                cron, enabled, last);
    }
}
