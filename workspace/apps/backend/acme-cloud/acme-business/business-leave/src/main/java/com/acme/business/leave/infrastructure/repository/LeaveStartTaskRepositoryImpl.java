package com.acme.business.leave.infrastructure.repository;

import com.acme.business.leave.domain.LeaveStartTask;
import com.acme.business.leave.domain.LeaveStartTaskStatus;
import com.acme.business.leave.infrastructure.mapper.LeaveStartTaskMapper;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 流程启动补偿任务仓储实现（MyBatis-Flex 底座；乐观锁/审计/软删/租户过滤由框架监听器承载）。 */
@Repository("leaveStartTaskRepository")
public class LeaveStartTaskRepositoryImpl extends FlexRepositorySupport<LeaveStartTask>
        implements LeaveStartTaskRepository {

    public LeaveStartTaskRepositoryImpl(LeaveStartTaskMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<LeaveStartTask> findById(Long id) {
        return Optional.ofNullable(mapper().selectOneById(id));
    }

    @Override
    public Optional<LeaveStartTask> findByLeaveId(Long leaveId) {
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()
                .where(LeaveStartTask::getLeaveId).eq(leaveId)
                .limit(1)));
    }

    @Override
    public List<LeaveStartTask> findRetryable(Instant now, long graceMs, long leaseMs) {
        // 扫描三腿（互斥：attempts=0/≥1 互斥、PENDING/RUNNING 互斥）拆为三条独立 AND 查询——
        // 避免扁平 OR 链与框架自动追加的 deleted=false 逻辑删条件的优先级耦合；跨租户捞取
        // （扫描线程无上下文时 flex tenantId 列不过滤，委派单事实 2）
        List<LeaveStartTask> due = new ArrayList<>();
        // 腿 1：PENDING 且 attempts≥1 且 next_retry_at≤now（指数退避到期重投）
        due.addAll(mapper().selectListByQuery(QueryWrapper.create()
                .where(LeaveStartTask::getStatus).eq(LeaveStartTaskStatus.PENDING)
                .and(LeaveStartTask::getAttempts).ge(1)
                .and(LeaveStartTask::getNextRetryAt).le(now)
                .orderBy(LeaveStartTask::getId, true)));
        // 腿 2：PENDING 且 attempts=0 且 created_at<now-grace（JVM 提交后、内联启动前崩溃防丢失）
        due.addAll(mapper().selectListByQuery(QueryWrapper.create()
                .where(LeaveStartTask::getStatus).eq(LeaveStartTaskStatus.PENDING)
                .and(LeaveStartTask::getAttempts).eq(0)
                .and(LeaveStartTask::getCreatedAt).lt(now.minusMillis(graceMs))
                .orderBy(LeaveStartTask::getId, true)));
        // 腿 3：RUNNING 且 updated_at<now-lease（执行器崩溃租约回收，回 PENDING 重投）
        due.addAll(mapper().selectListByQuery(QueryWrapper.create()
                .where(LeaveStartTask::getStatus).eq(LeaveStartTaskStatus.RUNNING)
                .and(LeaveStartTask::getUpdatedAt).lt(now.minusMillis(leaseMs))
                .orderBy(LeaveStartTask::getId, true)));
        return due;
    }
}
