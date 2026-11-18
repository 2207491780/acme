package com.acme.platform.infra.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.infra.domain.JobExecution;
import com.acme.platform.infra.infrastructure.mapper.JobExecutionMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** 任务执行记录仓库实现（MyBatis-Flex 底座）。 */
@Repository
public class JobExecutionRepositoryImpl extends FlexRepositorySupport<JobExecution>
        implements JobExecutionRepository {

    public JobExecutionRepositoryImpl(JobExecutionMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<JobExecution> findLatest(String jobKey) {
        JobExecution latest = mapper().selectOneByQuery(QueryWrapper.create()
                .where(new QueryColumn("job_key").eq(jobKey))
                .orderBy(new QueryColumn("started_at"), false)
                .orderBy(new QueryColumn("id"), false)
                .limit(1));
        return Optional.ofNullable(latest);
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
