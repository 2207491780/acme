package com.acme.business.leave.infrastructure.repository;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.infrastructure.mapper.LeaveRequestMapper;
import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 请假单仓储实现（MyBatis-Flex 底座；乐观锁/审计/软删/租户过滤由框架监听器承载）。 */
@Repository("leaveRequestRepository")
public class LeaveRequestRepositoryImpl extends FlexRepositorySupport<LeaveRequest>
        implements LeaveRequestRepository {

    public LeaveRequestRepositoryImpl(LeaveRequestMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<LeaveRequest> findById(Long id) {
        return Optional.ofNullable(mapper().selectOneById(id));
    }

    @Override
    public Optional<LeaveRequest> findByInstanceId(Long instanceId) {
        return Optional.ofNullable(mapper().selectOneByQuery(QueryWrapper.create()
                .where(LeaveRequest::getInstanceId).eq(instanceId)
                .orderBy(LeaveRequest::getId, false)
                .limit(1)));
    }
}
