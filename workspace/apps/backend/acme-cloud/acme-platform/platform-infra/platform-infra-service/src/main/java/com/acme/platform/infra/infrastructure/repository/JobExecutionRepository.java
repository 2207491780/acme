package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.infra.domain.JobExecution;

import java.time.Instant;
import java.util.Optional;

/** 任务执行记录仓库（手动触发与调度统一落库面）。 */
public interface JobExecutionRepository extends FlexRepository<JobExecution> {

    /** 最近一次执行记录（按 started_at 倒序；无记录返回空）。 */
    Optional<JobExecution> findLatest(String jobKey);

    /** 回填终态（status/finished_at/error；执行完成与否均落记录，REQ-20261114-071607）。 */
    void markFinished(Long id, String status, Instant finishedAt, String error);
}
