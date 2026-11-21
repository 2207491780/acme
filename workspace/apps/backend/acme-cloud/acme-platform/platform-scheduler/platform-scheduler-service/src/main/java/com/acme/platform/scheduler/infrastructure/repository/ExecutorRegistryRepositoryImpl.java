package com.acme.platform.scheduler.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.scheduler.api.ExecutorStatus;
import com.acme.platform.scheduler.domain.SchExecutorRegistry;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 执行器注册仓储实现（MyBatis-Flex 底座）。 */
@Repository("schedulerExecutorRegistryRepository")
public class ExecutorRegistryRepositoryImpl extends FlexRepositorySupport<SchExecutorRegistry>
        implements ExecutorRegistryRepository {

    public ExecutorRegistryRepositoryImpl(
            com.acme.platform.scheduler.infrastructure.mapper.SchExecutorRegistryMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<SchExecutorRegistry> findByAppAndInstance(String appName, String instanceId) {
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()
                .where(SchExecutorRegistry::getAppName).eq(appName)
                .and(SchExecutorRegistry::getInstanceId).eq(instanceId)));
    }

    @Override
    public List<SchExecutorRegistry> findByAppName(String appName) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchExecutorRegistry::getAppName).eq(appName)
                .orderBy(SchExecutorRegistry::getId, true));
    }

    @Override
    public List<SchExecutorRegistry> findStale(Instant heartbeatBefore) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(SchExecutorRegistry::getStatus).eq(ExecutorStatus.ONLINE)
                .and(SchExecutorRegistry::getHeartbeatAt).lt(heartbeatBefore));
    }
}
