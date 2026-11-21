package com.acme.platform.scheduler.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.scheduler.api.ExecutionState;
import com.acme.platform.scheduler.domain.SchJobExecution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 执行历史仓储（结果记录只增改状态，不物理删除）。 */
public interface JobExecutionRepository extends FlexRepository<SchJobExecution> {

    /** 待下发执行（内核下发扫描：PENDING 且 fire_time <= now——首发与重试链统一恢复口径）。 */
    List<SchJobExecution> findPendingDue(Instant now);

    /** 任务内非终态执行（同 job 串行门：存在即跳过本次 SCHEDULE 触发）。 */
    boolean existsNonTerminalByDefinitionId(Long definitionId);

    /** 同定义同触发时刻的首发 SCHEDULE 执行（触发幂等的 DB 侧预检，唯一索引兜底并发）。 */
    boolean existsScheduleExecution(Long definitionId, Instant fireTime);

    /** 定义最近一条执行（同 job 串行门精确口径与运维展示）。 */
    Optional<SchJobExecution> findLatestByDefinitionId(Long definitionId);

    /** 任务键下非终态执行集合（巡检/运维口径）。 */
    List<SchJobExecution> findByState(ExecutionState state);
}
