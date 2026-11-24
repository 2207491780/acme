package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.domain.JobExecution;

import java.time.Instant;

/** 任务执行记录仓库（手动触发与调度统一落库面）。 */
public interface JobExecutionRepository extends FlexRepository<JobExecution> {

    /** 回填终态（status/finished_at/error；执行完成与否均落记录，REQ-20261114-071607）。 */
    void markFinished(Long id, String status, Instant finishedAt, String error);
}
