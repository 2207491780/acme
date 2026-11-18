package com.acme.platform.infra.application;

import com.acme.platform.infra.domain.JobExecution;
import com.acme.platform.infra.infrastructure.repository.JobExecutionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 任务执行记录器（REQ-20261114-071607 §变更清单 sys_job_execution）：锁内起记录（RUNNING）、
 * 终态回填（SUCCESS/FAILED + finished_at/error）——执行完成与否均落记录（AC-13）；
 * 记录创建于 ShedLock 互斥成功之后（未获锁不产生执行记录）。
 */
@Service
public class JobExecutionRecorder {

    private final JobExecutionRepository repository;

    public JobExecutionRecorder(JobExecutionRepository repository) {
        this.repository = repository;
    }

    /** 起记录（status=RUNNING；返回执行记录 id 作为手动触发返回值）。 */
    public Long start(String jobKey, String triggerType) {
        JobExecution execution = new JobExecution(jobKey, JobExecution.STATUS_RUNNING,
                Instant.now(), triggerType);
        return repository.save(execution).getId();
    }

    /** 终态回填（error 仅失败非空）。 */
    public void finish(Long executionId, String status, String error) {
        repository.markFinished(executionId, status, Instant.now(), error);
    }
}
