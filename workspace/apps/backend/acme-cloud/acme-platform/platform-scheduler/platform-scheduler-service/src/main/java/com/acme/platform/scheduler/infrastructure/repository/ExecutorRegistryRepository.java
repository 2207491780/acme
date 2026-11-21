package com.acme.platform.scheduler.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.scheduler.domain.SchExecutorRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 执行器注册仓储（注册/心跳幂等 upsert）。 */
public interface ExecutorRegistryRepository extends FlexRepository<SchExecutorRegistry> {

    /** 按应用+实例定位（upsert 键）。 */
    Optional<SchExecutorRegistry> findByAppAndInstance(String appName, String instanceId);

    /** 应用下全部实例（下发寻址候选集）。 */
    List<SchExecutorRegistry> findByAppName(String appName);

    /** 心跳早于阈值的实例（失联巡检：ONLINE → OFFLINE 回写）。 */
    List<SchExecutorRegistry> findStale(Instant heartbeatBefore);
}
