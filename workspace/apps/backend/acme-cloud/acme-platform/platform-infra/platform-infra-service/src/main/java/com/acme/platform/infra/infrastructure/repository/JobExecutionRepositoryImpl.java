package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.infra.domain.JobExecution;
import com.acme.platform.infra.infrastructure.mapper.JobExecutionMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** 任务执行记录仓库实现（MyBatis-Flex 底座）。 */
@Repository
public class JobExecutionRepositoryImpl extends FlexRepositorySupport<JobExecution>
        implements JobExecutionRepository {

    public JobExecutionRepositoryImpl(JobExecutionMapper mapper) {
        super(mapper);
    }

    @Override
    public void markFinished(Long id, String status, Instant finishedAt, String error) {
        JobExecution execution = mapper().selectOneById(id);
        if (execution == null) {
            return;
        }
        execution.setStatus(status);
        execution.setFinishedAt(finishedAt);
        execution.setError(error);
        save(execution);
    }
}
