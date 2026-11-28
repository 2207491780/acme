package com.acme.business.leave.infrastructure.repository;

import com.acme.business.leave.domain.LeaveRequest;

import java.util.Optional;

/**
 * 请假单仓储契约（FR 仓储语义——保存乐观锁/审计/软删由 FlexRepositorySupport 承载）。
 */
public interface LeaveRequestRepository {

    /** 保存（插入/乐观锁更新）。 */
    LeaveRequest save(LeaveRequest leave);

    /** 主键装载。 */
    Optional<LeaveRequest> findById(Long id);

    /** 主键 + 用户装载（详情 owner 直读判定；租户维由框架监听器自动过滤，REQ-20261127-235808）。 */
    Optional<LeaveRequest> findByIdAndUserId(Long id, String userId);

    /** 按流程实例装载（INSTANCE_COMPLETED 事件回写兜底锚点）。 */
    Optional<LeaveRequest> findByInstanceId(Long instanceId);
}
