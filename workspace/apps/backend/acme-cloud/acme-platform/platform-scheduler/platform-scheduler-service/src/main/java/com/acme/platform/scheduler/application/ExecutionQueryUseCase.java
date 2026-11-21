package com.acme.platform.scheduler.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.domain.SchJobExecution;
import com.acme.platform.scheduler.infrastructure.repository.JobExecutionRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 执行历史查询用例（确认④：分页按 jobKey/state/fireTime 区间过滤；fire_time 倒序稳定排序）。
 */
@Service
public class ExecutionQueryUseCase {

    private final JobExecutionRepository executionRepository;

    public ExecutionQueryUseCase(JobExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    /**
     * 执行历史分页（fire_time 倒序，同毫秒按 id 倒序稳定排序——infra JobUseCase.listExecutions 同口径）。
     */
    public PageResult<ExecutionView> list(String jobKey, ExecutionState state,
                                          Instant fireTimeFrom, Instant fireTimeTo, PageQuery query) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (jobKey != null && !jobKey.isBlank()) {
            wrapper.and(SchJobExecution::getJobKey).eq(jobKey.trim());
        }
        if (state != null) {
            wrapper.and(SchJobExecution::getState).eq(state);
        }
        if (fireTimeFrom != null) {
            wrapper.and(SchJobExecution::getFireTime).ge(fireTimeFrom);
        }
        if (fireTimeTo != null) {
            wrapper.and(SchJobExecution::getFireTime).le(fireTimeTo);
        }
        wrapper.orderBy(SchJobExecution::getFireTime, false).orderBy(SchJobExecution::getId, false);
        PageResult<SchJobExecution> page = executionRepository.findAll(wrapper, query.page(), query.size());
        List<ExecutionView> records = page.records().stream().map(ExecutionQueryUseCase::toView).toList();
        return new PageResult<>(records, page.total());
    }

    private static ExecutionView toView(SchJobExecution execution) {
        return new ExecutionView(String.valueOf(execution.getId()),
                String.valueOf(execution.getDefinitionId()), execution.getJobKey(),
                execution.getTriggerType() == null ? null : execution.getTriggerType().name(),
                execution.getFireTime(),
                execution.getState() == null ? null : execution.getState().name(),
                execution.getAttempt(), execution.getShardIndex(), execution.getResult(),
                execution.getError(), execution.getCostMs(), execution.getTraceId());
    }
}
