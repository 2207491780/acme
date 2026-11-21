package com.acme.platform.workflow.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.framework.tenant.support.IgnoreTenant;
import com.acme.platform.workflow.domain.WfTimerJob;
import com.acme.platform.workflow.infrastructure.mapper.WorkflowTimerJobMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 定时任务仓储实现（MyBatis-Flex 底座；兜底扫描/注销/幂等定位三面 + fire 通道旁路声明）。 */
@Repository("workflowTimerJobRepository")
public class WfTimerJobRepositoryImpl extends FlexRepositorySupport<WfTimerJob>
        implements WfTimerJobRepository {

    public WfTimerJobRepositoryImpl(WorkflowTimerJobMapper mapper) {
        super(mapper);
    }

    @Override
    @IgnoreTenant("workflow-timer-fallback-scan")
    public List<WfTimerJob> findPendingDue(Instant now) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfTimerJob::getStatus).eq(WfTimerJob.STATUS_PENDING)
                .and(WfTimerJob::getDueAt).le(now));
    }

    @Override
    public List<WfTimerJob> findPendingByInstanceIdAndNodeId(Long instanceId, String nodeId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfTimerJob::getInstanceId).eq(instanceId)
                .and(WfTimerJob::getNodeId).eq(nodeId)
                .and(WfTimerJob::getStatus).eq(WfTimerJob.STATUS_PENDING));
    }

    @Override
    public List<WfTimerJob> findPendingByInstanceId(Long instanceId) {
        return mapper().selectListByQuery(QueryWrapper.create()
                .where(WfTimerJob::getInstanceId).eq(instanceId)
                .and(WfTimerJob::getStatus).eq(WfTimerJob.STATUS_PENDING));
    }

    @Override
    public Optional<WfTimerJob> findBySchedulerJobKey(String schedulerJobKey) {
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()
                .where(WfTimerJob::getSchedulerJobKey).eq(schedulerJobKey)));
    }

    @Override
    @IgnoreTenant("workflow-timer-fire")
    public Optional<WfTimerJob> findByIdGlobal(Long id) {
        return findById(id);
    }

    @Override
    @IgnoreTenant("workflow-timer-fire")
    public int casFire(Long id) {
        // 原子 CAS（round-2 D4）：SET status='FIRED' WHERE id=? AND status='PENDING'——
        // fire 端点与本地兜底双通道的权威抢占面，0 行=已被并发通道消费（静默跳过）。
        // ignoreNulls=true：patch 仅携 status，禁将其余列清空（旧实现 false 致全列置 null
        // 触发 instance_id NOT NULL 违约——fire 500 SYS-00-0006 根因）
        WfTimerJob patch = new WfTimerJob();
        patch.setStatus(WfTimerJob.STATUS_FIRED);
        return mapper().updateByQuery(patch, true, QueryWrapper.create()
                .where(WfTimerJob::getId).eq(id)
                .and(WfTimerJob::getStatus).eq(WfTimerJob.STATUS_PENDING));
    }
}
