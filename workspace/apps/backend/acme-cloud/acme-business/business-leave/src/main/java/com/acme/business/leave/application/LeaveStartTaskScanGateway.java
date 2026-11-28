package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveStartTask;
import com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository;
import com.acme.framework.tenant.support.IgnoreTenant;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 补偿任务跨租户扫描门面（REQ-20261128-092210 round-1）：{@code @IgnoreTenant} 切面须经
 * Spring 代理触发（自调用/同 bean 直调不走代理），故从 {@code LeaveStartRetryJob} 独立成
 * bean 供其注入调用——旁路仅覆盖**捞取读面**（{@code FlexTenantFactory} 缺上下文返回
 * UNBOUND_TENANT 哨兵：读恒空集，跨租户捞取必须走旁路；12.4.2 访问点登记）。
 *
 * <p>认领/回收/执行写面**不进旁路**：由 job 逐任务绑定行租户上下文承载（TenantWriteGuard
 * fail-closed 语义保持，防御纵深不放宽）。旁路审计经 {@code @IgnoreTenant} 切面单点记录。</p>
 */
@Component("leaveStartTaskScanGateway")
public class LeaveStartTaskScanGateway {

    private final LeaveStartTaskRepository taskRepository;

    public LeaveStartTaskScanGateway(LeaveStartTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /** 跨租户捞取三腿到期任务（委托 {@link LeaveStartTaskRepository#findRetryable}，旁路读全租户）。 */
    @IgnoreTenant("leave.start-retry-scan")
    public List<LeaveStartTask> findDueTasks(Instant now, long graceMs, long leaseMs) {
        return taskRepository.findRetryable(now, graceMs, leaseMs);
    }
}
