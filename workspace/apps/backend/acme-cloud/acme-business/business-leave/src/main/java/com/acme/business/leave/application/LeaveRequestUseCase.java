package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.process.LeaveProcessLauncher;
import com.acme.common.contract.InvalidRequestException;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.util.IdGenerator;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 请假单用例（REQ-20261122-233425 确认⑤端到端试点）。状态机=事件驱动（contract ⑤ 状态链序）：
 * <ul>
 *   <li>申请（用户面）：落库 APPLYING → {@link WorkflowRuntimeClient} 启动流程绑 businessKey
 *       （调用方语义键）→ 回写 instance_id。APPROVING 落点=审批任务办结事件（见下）；</li>
 *   <li>APPROVING：TASK_COMPLETED 事件自消费（审批任务办结、HR 备案之前的状态域）；</li>
 *   <li>FILED：HR 备案 ServiceTask 委托 handler 落库（APPROVING→FILED，幂等——重复下发直返）；</li>
 *   <li>DONE/REJECTED：INSTANCE_COMPLETED 事件自消费——备案链走通（实例变量 filed=true，由
 *       handler 结果变量经回调并入）→ DONE；未走备案链 → REJECTED。</li>
 * </ul>
 * 上下文口径：用户面经网关/边缘过滤链绑定 UserContext/TenantContext（FR-007）；ServiceTask
 * handler/事件消费在 X-Acme-Token（handler）/消息消费线程运行，实体携带 tenant_id 显式回放。
 * 跨域调用：单体经 LocalWorkflowRuntimeClient/本地部署器短路（确认⑥），分布式经 Feign。
 */
@Service("leaveRequestUseCase")
public class LeaveRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(LeaveRequestUseCase.class);

    /** HR 备案等待 APPROVING 的有界窗口毫秒（TL round-4：5s；TASK_COMPLETED 消费滞后的容忍上界）。 */
    static final long FILE_AWAIT_TIMEOUT_MS = 5_000L;

    /** HR 备案等待轮询间隔毫秒。 */
    static final long FILE_AWAIT_POLL_MS = 200L;

    private final LeaveRequestRepository leaveRepository;
    private final WorkflowRuntimeClient workflowRuntimeClient;
    private final ObjectProvider<LeaveProcessLauncher> processLauncher;
    private final IdGenerator idGenerator;
    private final LeaveAuthorizationGateway leaveAuthorizationGateway;

    public LeaveRequestUseCase(LeaveRequestRepository leaveRepository,
                               WorkflowRuntimeClient workflowRuntimeClient,
                               ObjectProvider<LeaveProcessLauncher> processLauncher,
                               IdGenerator idGenerator,
                               LeaveAuthorizationGateway leaveAuthorizationGateway) {
        this.leaveRepository = leaveRepository;
        this.workflowRuntimeClient = workflowRuntimeClient;
        this.processLauncher = processLauncher;
        this.idGenerator = idGenerator;
        this.leaveAuthorizationGateway = leaveAuthorizationGateway;
    }

    /** 申请（落库 APPLYING + 启动流程绑 businessKey；APPROVING 随审批办结事件，确认⑤状态链序）。 */
    public LeaveRequestView apply(LeaveApplyCommand command) {
        String userId = currentUserId();
        String tenantId = currentTenantId();
        Long definitionId = processLauncher.getObject().pilotDefinitionId();
        LeaveRequest leave = new LeaveRequest();
        // 主键预分配（AuditFillListener 只补 null）：事件/对账面锚点
        leave.setId(idGenerator.nextId());
        leave.setTenantId(tenantId);
        leave.setUserId(userId);
        leave.setUserName(userId);
        leave.setLeaveType(command.leaveType());
        leave.setReason(command.reason());
        leave.setStartTime(command.startTime());
        leave.setEndTime(command.endTime());
        leave.setStatus(LeaveStatus.APPLYING);
        leaveRepository.save(leave);

        ContractResult<Long> started = workflowRuntimeClient.startProcessInstance(
                new InternalProcessInstanceStartRequest(definitionId, command.businessKey(),
                        Map.of("leaveId", leave.getId(), "leaveType", command.leaveType()),
                        userId));
        leave.setInstanceId(started.payload());
        leaveRepository.save(leave);
        log.info("请假申请已受理并启动流程：leaveId={} instanceId={} businessKey={}",
                leave.getId(), leave.getInstanceId(), command.businessKey());
        return toView(leave);
    }

    /**
     * HR 备案（ServiceTask 委托 handler 入口；APPROVING→FILED，重复下发幂等；instanceId 锚点）。
     * 状态守卫（TL round-4 delta）：APPLYING → 有界等待 APPROVING（TASK_COMPLETED 事件消费可能
     * 滞后于 ServiceTask 下发——能到达 ServiceTask 的 token 必经审批办结，APPLYING 恒为陈旧读；
     * 与 onTaskCompleted 单向推进守卫无回退冲突）后照写 FILED，超时仍 APPLYING 亦照写+warn 留痕；
     * 终态（DONE/REJECTED）→ 不写、filed=false+reason（真错发暴露面不弱化）。
     */
    public Map<String, Object> fileByServiceTask(Long instanceId, String businessKey) {
        LeaveRequest leave = resolveByRuntime(instanceId, businessKey);
        if (leave.getStatus() == LeaveStatus.APPLYING) {
            awaitApproving(instanceId, businessKey);
            // 重载最新行（等待期 TASK_COMPLETED 消费者可能已推进并递增乐观锁版本，陈旧实体直存会 STALE_WRITE）
            leave = resolveByRuntime(instanceId, businessKey);
        }
        if (leave.getStatus() == LeaveStatus.FILED) {
            log.info("HR 备案幂等直返（已 FILED）：leaveId={}", leave.getId());
            return Map.of("filed", Boolean.TRUE, "leaveId", leave.getId());
        }
        if (leave.getStatus() == LeaveStatus.DONE || leave.getStatus() == LeaveStatus.REJECTED) {
            log.warn("HR 备案收到终态请假单（真错发暴露面，不写）：leaveId={} status={}",
                    leave.getId(), leave.getStatus());
            return Map.of("filed", Boolean.FALSE, "leaveId", leave.getId(),
                    "reason", "STATUS_" + leave.getStatus().name());
        }
        if (leave.getStatus() == LeaveStatus.APPLYING) {
            log.warn("HR 备案等待 APPROVING 超时仍为 APPLYING，照写 FILED"
                    + "（token 必经审批办结，APPLYING 恒为事件消费滞后陈旧读）：leaveId={}", leave.getId());
        }
        leave.setStatus(LeaveStatus.FILED);
        leaveRepository.save(leave);
        log.info("HR 备案完成：leaveId={} instanceId={} businessKey={}",
                leave.getId(), instanceId, businessKey);
        return Map.of("filed", Boolean.TRUE, "leaveId", leave.getId());
    }

    /** 有界等待审批域推进（5s × 200ms 轮询重读；离开 APPLYING 即返回）。 */
    private void awaitApproving(Long instanceId, String businessKey) {
        long deadline = System.currentTimeMillis() + FILE_AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            LeaveRequest current = resolveByRuntimeQuietly(instanceId, businessKey);
            if (current == null || current.getStatus() != LeaveStatus.APPLYING) {
                return;
            }
            try {
                Thread.sleep(FILE_AWAIT_POLL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * TASK_COMPLETED 事件回写（确认⑤状态链：APPROVING=审批办结后、HR 备案前状态域）。
     * 仅 APPLYING→APPROVING 单向推进（FILED 及终态不回退）。
     */
    public void onTaskCompleted(Long instanceId, String businessKey) {
        LeaveRequest leave = resolveByRuntimeQuietly(instanceId, businessKey);
        if (leave == null || leave.getStatus() != LeaveStatus.APPLYING) {
            return;
        }
        leave.setStatus(LeaveStatus.APPROVING);
        leaveRepository.save(leave);
        log.info("请假单进入审批域：leaveId={} instanceId={}", leave.getId(), instanceId);
    }

    /** TASK_REJECTED 事件回写（审批驳回 → REJECTED 终态；终态幂等跳过）。 */
    public void onTaskRejected(Long instanceId, String businessKey) {
        LeaveRequest leave = resolveByRuntimeQuietly(instanceId, businessKey);
        if (leave == null || isTerminal(leave.getStatus())) {
            return;
        }
        leave.setStatus(LeaveStatus.REJECTED);
        leaveRepository.save(leave);
        log.info("请假单驳回终态回写：leaveId={} instanceId={}", leave.getId(), instanceId);
    }

    /**
     * INSTANCE_COMPLETED 事件回写终态（确认⑤⑤；备案链走通判定=实例变量 filed=true（handler
     * 结果变量经回调并入实例变量）或 leave 已 FILED；否则 REJECTED——实例终结未走备案链）。
     * 终态/未知锚点幂等跳过（消息重复投递/他域键）。
     */
    public void onInstanceCompleted(Long instanceId, String businessKey) {
        LeaveRequest leave = resolveByRuntimeQuietly(instanceId, businessKey);
        if (leave == null) {
            log.warn("INSTANCE_COMPLETED 回写跳过（非本域请假单）：instanceId={} businessKey={}",
                    instanceId, businessKey);
            return;
        }
        if (isTerminal(leave.getStatus())) {
            log.info("终态幂等跳过（重复消费）：leaveId={} status={}", leave.getId(), leave.getStatus());
            return;
        }
        FiledProbe probe = leave.getStatus() == LeaveStatus.FILED
                ? FiledProbe.TRUE : instanceVariablesFiled(instanceId);
        if (probe == FiledProbe.UNKNOWN) {
            log.warn("INSTANCE_COMPLETED 终态回写跳过（实例变量查询 UNKNOWN，不写 REJECTED 终态，"
                    + "留待消息重投/对账）：instanceId={} businessKey={}", instanceId, businessKey);
            return;
        }
        leave.setStatus(probe == FiledProbe.TRUE ? LeaveStatus.DONE : LeaveStatus.REJECTED);
        leaveRepository.save(leave);
        log.info("请假单终态回写完成：leaveId={} status={} instanceId={}",
                leave.getId(), leave.getStatus(), instanceId);
    }

    /**
     * 详情（按 id；越权收口 REQ-20261127-235808 决策③/确认⑦案B）：owner 直读（不经权限服务）
     * → 非 owner 按授权权限码（workflow:task:list / workflow:instance:manage 任一持有）经
     * platform-system 实查放行 → 全不命中与「单据不存在」同口径（400，不泄露单据存在性）。
     * 用户上下文缺失按非 owner 处置（未认证主体经权限链 fail-closed=false）；授权判定
     * UNKNOWN（权限服务异常，网关 fail-closed=false）不放行；租户维由框架监听器自动过滤。
     */
    public LeaveRequestView get(Long id) {
        UserContext user = UserContext.get();
        if (user != null && user.userId() != null && !user.userId().isBlank()) {
            Optional<LeaveRequest> owned = leaveRepository.findByIdAndUserId(id, user.userId());
            if (owned.isPresent()) {
                return toView(owned.get());
            }
        }
        LeaveRequest leave = leaveRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("请假单不存在：" + id));
        if (leaveAuthorizationGateway.hasPermission(SystemPermissions.WORKFLOW_TASK_LIST)
                || leaveAuthorizationGateway.hasPermission(SystemPermissions.WORKFLOW_INSTANCE_MANAGE)) {
            return toView(leave);
        }
        // 越权与查无同口径响应（不泄露单据存在性）
        throw new InvalidRequestException("请假单不存在：" + id);
    }

    /**
     * 实例变量备案标记三态探询（REQ-20261127-235808 确认⑦②）：正常返回且 filed=true → TRUE；
     * 正常返回但未备案（payload/variables 缺失、键缺失、值非 true）→ FALSE；查询抛异常
     * （含 BizException——framework-feign 错误解码面）→ UNKNOWN（调用方不写终态）。
     */
    private FiledProbe instanceVariablesFiled(Long instanceId) {
        try {
            ContractResult<com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView> detail =
                    workflowRuntimeClient.processInstanceDetail(instanceId);
            Object filed = detail.payload() == null || detail.payload().variables() == null
                    ? null : detail.payload().variables().get("filed");
            return Boolean.TRUE.equals(filed) || "true".equals(String.valueOf(filed))
                    ? FiledProbe.TRUE : FiledProbe.FALSE;
        } catch (RuntimeException ex) {
            log.warn("实例变量备案标记读取失败（UNKNOWN，终态回写跳过）：instanceId={}", instanceId, ex);
            return FiledProbe.UNKNOWN;
        }
    }

    /** 实例备案标记探询三态：TRUE=已备案 / FALSE=明确未备案 / UNKNOWN=查询异常（不落终态）。 */
    private enum FiledProbe {
        TRUE, FALSE, UNKNOWN
    }

    private static boolean isTerminal(LeaveStatus status) {
        return status == LeaveStatus.DONE || status == LeaveStatus.REJECTED;
    }

    /** 运行时锚点解析（instanceId 唯一锚点——businessKey 仅承载日志/异常消息，确认记录②）。 */
    private LeaveRequest resolveByRuntime(Long instanceId, String businessKey) {
        LeaveRequest leave = resolveByRuntimeQuietly(instanceId, businessKey);
        if (leave == null) {
            throw new InvalidRequestException("请假单不存在：instanceId=" + instanceId
                    + " businessKey=" + businessKey);
        }
        return leave;
    }

    /** 运行时锚点解析（instanceId 唯一锚点，REQ-20261127-235808 确认②——数字形态 businessKey 兜底已删，防写错单据）。 */
    private LeaveRequest resolveByRuntimeQuietly(Long instanceId, String businessKey) {
        if (instanceId != null) {
            var found = leaveRepository.findByInstanceId(instanceId);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }

    private static LeaveRequestView toView(LeaveRequest leave) {
        return new LeaveRequestView(leave.getId(), leave.getUserName(), leave.getLeaveType(),
                leave.getReason(), leave.getStartTime(), leave.getEndTime(), leave.getStatus(),
                leave.getInstanceId(), String.valueOf(leave.getId()));
    }

    private static String currentUserId() {
        UserContext user = UserContext.get();
        if (user == null || user.userId() == null || user.userId().isBlank()) {
            throw new InvalidRequestException("缺少用户上下文");
        }
        return user.userId();
    }

    private static String currentTenantId() {
        TenantContext tenant = TenantContext.get();
        if (tenant == null || tenant.tenantId() == null || tenant.tenantId().isBlank()) {
            throw new InvalidRequestException("缺少租户上下文");
        }
        return tenant.tenantId();
    }
}
