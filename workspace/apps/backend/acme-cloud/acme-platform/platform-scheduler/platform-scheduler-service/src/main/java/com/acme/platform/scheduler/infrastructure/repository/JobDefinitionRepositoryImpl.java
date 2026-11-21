package com.acme.platform.scheduler.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.scheduler.api.SchedulerJobStatus;
import com.acme.platform.scheduler.domain.SchJobDefinition;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 任务定义仓储实现（MyBatis-Flex 底座）。 */
@Repository("schedulerJobDefinitionRepository")
public class JobDefinitionRepositoryImpl extends FlexRepositorySupport<SchJobDefinition>
        implements JobDefinitionRepository {

    public JobDefinitionRepositoryImpl(com.acme.platform.scheduler.infrastructure.mapper.SchJobDefinitionMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<SchJobDefinition> findByJobKey(String jobKey) {
        return Optional.ofNullable(mapper().selectOneByQuery(
                QueryWrapper.create().where(SchJobDefinition::getJobKey).eq(jobKey)));
    }

    @Override
    public List<SchJobDefinition> findDue(Instant now) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchJobDefinition::getStatus).eq(SchedulerJobStatus.ENABLED)
                .and(SchJobDefinition::getNextFireTime).isNotNull()
                .and(SchJobDefinition::getNextFireTime).le(now));
    }

    @Override
    public List<SchJobDefinition> findUpcomingBetween(Instant lowerExclusive, Instant upperInclusive) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchJobDefinition::getStatus).eq(SchedulerJobStatus.ENABLED)
                .and(SchJobDefinition::getNextFireTime).isNotNull()
                .and(SchJobDefinition::getNextFireTime).gt(lowerExclusive)
                .and(SchJobDefinition::getNextFireTime).le(upperInclusive));
    }

    @Override
    public List<SchJobDefinition> findEnabledWithoutNextFire() {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchJobDefinition::getStatus).eq(SchedulerJobStatus.ENABLED)
                .and(SchJobDefinition::getNextFireTime).isNull());
    }

    @Override
    public List<SchJobDefinition> findByStatus(SchedulerJobStatus status) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchJobDefinition::getStatus).eq(status)
                .orderBy(SchJobDefinition::getJobKey, true));
    }
}
