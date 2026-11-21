package com.acme.platform.scheduler.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.acme.platform.scheduler.api.SchedulerJobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 任务定义仓储（平台级控制面，无租户过滤）。 */
public interface JobDefinitionRepository extends FlexRepository<SchJobDefinition> {

    /** 按任务键查定义（创建查重 / 一次性任务注册查重 / 触发链路定位）。 */
    Optional<SchJobDefinition> findByJobKey(String jobKey);

    /** 到期定义（调度内核 DB 兜底扫描：启用且 next_fire_time <= now）。 */
    List<SchJobDefinition> findDue(Instant now);

    /** 窗口内即将到期定义（时间轮回填：now < next_fire_time <= upperBound）。 */
    List<SchJobDefinition> findUpcomingBetween(Instant lowerExclusive, Instant upperInclusive);

    /** 启用且未排期定义（启动恢复：next_fire_time IS NULL 计算初始排期）。 */
    List<SchJobDefinition> findEnabledWithoutNextFire();

    /** 按状态全集（管理面列表）。 */
    List<SchJobDefinition> findByStatus(SchedulerJobStatus status);
}
