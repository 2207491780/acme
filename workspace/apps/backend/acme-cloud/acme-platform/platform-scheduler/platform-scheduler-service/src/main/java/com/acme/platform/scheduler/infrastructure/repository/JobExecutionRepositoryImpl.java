package com.acme.platform.scheduler.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.api.ExecutionTriggerType;
import com.acme.platform.scheduler.domain.SchJobExecution;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 执行历史仓储实现（MyBatis-Flex 底座）。 */
@Repository("schedulerJobExecutionRepository")
public class JobExecutionRepositoryImpl extends FlexRepositorySupport<SchJobExecution>
        implements JobExecutionRepository {

    public JobExecutionRepositoryImpl(com.acme.platform.scheduler.infrastructure.mapper.SchJobExecutionMapper mapper) {
        super(mapper);
    }

    @Override
    public List<SchJobExecution> findPendingDue(Instant now) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchJobExecution::getState).eq(ExecutionState.PENDING)
                .and(SchJobExecution::getFireTime).le(now)
                .orderBy(SchJobExecution::getFireTime, true)
                .limit(100));
    }

    @Override
    public boolean existsNonTerminalByDefinitionId(Long definitionId) {
        // 计数用独立无排序 QueryWrapper（flex-test-assembly 手册第 5 条）
        return mapper().selectCountByQuery(QueryWrapper.create()
                .where(SchJobExecution::getDefinitionId).eq(definitionId)
                .and(SchJobExecution::getState).in(ExecutionState.NON_TERMINAL)) > 0;
    }

    @Override
    public boolean existsScheduleExecution(Long definitionId, Instant fireTime) {
        return mapper().selectCountByQuery(QueryWrapper.create()
                .where(SchJobExecution::getDefinitionId).eq(definitionId)
                .and(SchJobExecution::getFireTime).eq(fireTime)
                .and(SchJobExecution::getTriggerType).eq(ExecutionTriggerType.SCHEDULE)
                .and(SchJobExecution::getAttempt).eq(0)) > 0;
    }

    @Override
    public Optional<SchJobExecution> findLatestByDefinitionId(Long definitionId) {
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()
                .where(SchJobExecution::getDefinitionId).eq(definitionId)
                .orderBy(SchJobExecution::getFireTime, false)
                .orderBy(SchJobExecution::getId, false)
                .limit(1)));
    }

    @Override
    public List<SchJobExecution> findByState(ExecutionState state) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchJobExecution::getState).eq(state));
    }
}
