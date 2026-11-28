package com.acme.business.leave.application;

import com.acme.business.leave.domain.LeaveRequest;
import com.acme.business.leave.domain.LeaveStartTask;
import com.acme.business.leave.domain.LeaveStartTaskStatus;
import com.acme.business.leave.domain.LeaveStatus;
import com.acme.business.leave.infrastructure.client.LeaveAuthorizationGateway;
import com.acme.business.leave.infrastructure.repository.LeaveRequestRepository;
import com.acme.business.leave.infrastructure.repository.LeaveStartTaskRepository;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 请假单用例（REQ-20261122-233425 确认⑤端到端试点；REQ-20261128-092210 P1-03/P1-04 事务化与
 * 备案去轮询整改）。状态机=事件驱动+下发权威推进（contract ⑤ 状态链序）：
 * <ul>
 *   <li>申请（用户面）：{@code @Transactional} 同事务原子落库 leave_request(APPLYING) 与补偿任务行
 *       leave_start_task(PENDING, attempts=0)；流程启动移出事务——afterCommit 认领任务行（乐观锁
 *       CAS→RUNNING）→ {@link WorkflowRuntimeClient} 启动流程绑 businessKey → 成功回写
 *       instance_id + 任务 DONE；失败全捕获不反噬（响应恒 2xx），任务行 PENDING+指数退避+
 *       last_error 留痕，交 {@code LeaveStartRetryJob} 扫描补投；无活跃事务同步（直调/单测态）
 *       降级内联立即启动（framework-core BR-017 防丢失先例语义）。APPROVING 落点=审批任务办结
 *       （见下）；</li>
 *   <li>APPROVING：审批办结后、HR 备案前的状态域——TASK_COMPLETED 事件自消费，或备案下发权威
 *       推进第一步（见下）；</li>
 *   <li>FILED：HR 备案 ServiceTask 委托 handler 落库——下发即审批办结权威证据（token 必经审批
 *       UserTask）：APPLYING 收下发两步前向 APPROVING→FILED（各一次乐观锁落库，无状态跳写），
 *       APPROVING 收下单步落库；幂等——重复下发直返；</li>
 *   <li>DONE/REJECTED：INSTANCE_COMPLETED 事件自消费——备案链走通（实例变量 filed=true，由
 *       handler 结果变量经回调并入）→ DONE；未走备案链 → REJECTED。</li>
 * </ul>
 * 上下文口径：用户面经网关/边缘过滤链绑定 UserContext/TenantContext（FR-007）；ServiceTask
 * handler/事件消费/重试任务在 X-Acme-Token（handler）/消息消费/调度线程运行，按载荷/任务行
 * tenantId 显式回放。跨域调用：单体经 LocalWorkflowRuntimeClient/本地部署器短路（确认⑥），
 * 分布式经 Feign（启动失败解码为 BizException/连接异常，统一按 RuntimeException 捕获转补偿）。
 */
@Service("leaveRequestUseCase")
public class LeaveRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(LeaveRequestUseCase.class);

    /**
     * afterCommit 内联启动失败的补偿登记缺省退避基值毫秒（与重试任务缺省配置一致；配置面
     * {@code acme.leave.start-retry-backoff-base-ms} 由 LeaveStartRetryJob 在重试轮次承担——
     * 本类构造面固定六参，内联首败登记按缺省策略落库，重试轮次按配置策略收敛）。
     */
    static final long DEFAULT_START_RETRY_BACKOFF_BASE_MS = 1_000L;

    /** afterCommit 内联启动失败的补偿登记缺省尝试上限（同上，缺省配置一致）。 */
    static final int DEFAULT_START_RETRY_MAX_ATTEMPTS = 5;

    private final LeaveRequestRepository leaveRepository;
    private final LeaveStartTaskRepository startTaskRepository;
    private final WorkflowRuntimeClient workflowRuntimeClient;
    private final ObjectProvider<LeaveProcessLauncher> processLauncher;
    private final IdGenerator idGenerator;
    private final LeaveAuthorizationGateway leaveAuthorizationGateway;

    public LeaveRequestUseCase(LeaveRequestRepository leaveRepository,
                               WorkflowRuntimeClient workflowRuntimeClient,
                               ObjectProvider<LeaveProcessLauncher> processLauncher,
                               IdGenerator idGenerator,
                               LeaveAuthorizationGateway leaveAuthorizationGateway,
                               LeaveStartTaskRepository startTaskRepository) {
        this.leaveRepository = leaveRepository;
        this.workflowRuntimeClient = workflowRuntimeClient;
        this.processLauncher = processLauncher;
        this.idGenerator = idGenerator;
        this.leaveAuthorizationGateway = leaveAuthorizationGateway;
        this.startTaskRepository = startTaskRepository;
    }

    /**
     * 申请（REQ-20261128-092210 P1-03 事务化）：同事务原子落库请假单(APPLYING)与补偿任务行
     * （PENDING, attempts=0）；流程启动后置 afterCommit（成功 instance_id 于响应前回写——响应
     * view 恒携 instanceId=null，前端 core leave.ts 已声明可空；失败转补偿重试面，响应恒 2xx）。
     * 路径/@Valid 校验/鉴权不变。
     */
    @Transactional
    public LeaveRequestView apply(LeaveApplyCommand command) {
        String userId = currentUserId();
        String tenantId = currentTenantId();
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

        // 补偿任务行（同事务原子落库；流程启动失败/JVM 崩溃经此行重试补投——防丢失）
        LeaveStartTask startTask = new LeaveStartTask();
        startTask.setId(idGenerator.nextId());
        startTask.setTenantId(tenantId);
        startTask.setLeaveId(leave.getId());
        startTask.setStatus(LeaveStartTaskStatus.PENDING);
        startTask.setAttempts(0);
        startTaskRepository.save(startTask);

        // view 先于启动后置构建（afterCommit 在请求线程同步执行、发生于响应写出之前，DB 已回写，
        // 但 view 恒携 instanceId=null——两路径口径一致，前端已兼容可空）
        LeaveRequestView view = toView(leave);
        registerAfterCommitStart(leave, startTask, command.businessKey());
        log.info("请假申请已受理（流程启动后置 afterCommit）：leaveId={} taskId={} businessKey={}",
                leave.getId(), startTask.getId(), command.businessKey());
        return view;
    }

    /**
     * HR 备案（ServiceTask 委托 handler 入口；REQ-20261128-092210 P1-04 下发权威推进——无等待
     * 无轮询）。token 到达 hr-file ServiceTask=审批办结的权威证据（试点 BPMN 单链
     * start→approval→hr-file 无旁路，APPLYING 恒为事件消费滞后的陈旧读）：APPLYING 收下发两步
     * 前向 APPROVING→FILED（各一次乐观锁落库，无状态跳写），APPROVING 收下单步落库（与
     * onTaskCompleted 同义同守卫的单向推进）。终态守卫与幂等直返不变；瞬时异常（DB 故障/乐观锁
     * 冲突）原样上抛 → SDK 接收端点 5xx 反噬 → 引擎按试点节点属性重试（3 次×1s），耗尽挂起
     * SERVICE_TASK_FAILED，FILED 不被写入（终态判定仍归事件面）。
     */
    public Map<String, Object> fileByServiceTask(Long instanceId, String businessKey) {
        LeaveRequest leave = resolveByRuntime(instanceId, businessKey);
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
            // 下发权威推进第一步：APPROVING（无状态跳写——两步前向各一次乐观锁落库；
            // 与事件消费者并发时乐观锁裁决，败者 5xx 反噬引擎重试收敛，状态机无回退）
            leave.setStatus(LeaveStatus.APPROVING);
            leaveRepository.save(leave);
        }
        leave.setStatus(LeaveStatus.FILED);
        leaveRepository.save(leave);
        log.info("HR 备案完成：leaveId={} instanceId={} businessKey={}",
                leave.getId(), instanceId, businessKey);
        return Map.of("filed", Boolean.TRUE, "leaveId", leave.getId());
    }

    /**
     * TASK_COMPLETED 事件回写（确认⑤状态链：APPROVING=审批办结后、HR 备案前状态域）。
     * 仅 APPLYING→APPROVING 单向推进（FILED 及终态不回退；与备案下发权威推进并发时乐观锁
     * 裁决，消费侧见非 APPLYING 即跳过——REQ-20261128-092210 AC-16 收敛）。
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
     * instanceId 字段=流程启动异步回写结果（REQ-20261128-092210 P1-03），启动成功前为 null。
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
     * 流程启动+回写共享执行体（REQ-20261128-092210 P1-03 补偿面；afterCommit 内联与重试任务
     * 同径）。调用方契约：任务行已认领 RUNNING、调用线程已绑定任务行租户上下文（重试任务按
     * task.tenantId 显式回放；afterCommit 在请求线程上下文在场）。幂等守卫：leave 行已有
     * instance_id（此前执行者已启动、DONE 回写前中断）→ 任务收敛 DONE，不再二次启动。
     * 启动成功回写 instance_id + 任务 DONE；失败原样抛出 RuntimeException，由调用方登记补偿
     * （afterCommit 内联缺省策略 / 重试任务配置策略）。
     *
     * <p>重试补投 businessKey 取请假单 id 字符串形态（LeaveRequestView 对账锚点口径——
     * leave_request 无 business_key 列，首次启动的调用方 businessKey 不持久化；启动失败态
     * 无既有实例，重试锚点不产生对账冲突）。payload 判空防御：非成功信封 payload() 显式失败、
     * 成功信封空 data 同按启动失败处置（转补偿重试）。</p>
     */
    public void startProcessAndWriteback(LeaveRequest leave, LeaveStartTask task, String businessKey) {
        if (leave.getInstanceId() != null) {
            log.info("补偿启动幂等收敛（leave 已绑定实例，不再二次启动）：leaveId={} taskId={} instanceId={}",
                    leave.getId(), task.getId(), leave.getInstanceId());
            task.setStatus(LeaveStartTaskStatus.DONE);
            startTaskRepository.save(task);
            return;
        }
        Long definitionId = processLauncher.getObject().pilotDefinitionId();
        ContractResult<Long> started = workflowRuntimeClient.startProcessInstance(
                new InternalProcessInstanceStartRequest(definitionId, businessKey,
                        Map.of("leaveId", leave.getId(), "leaveType", leave.getLeaveType()),
                        leave.getUserId()));
        if (started == null || started.payload() == null) {
            throw new IllegalStateException("流程启动未返回实例 id：leaveId=" + leave.getId()
                    + " businessKey=" + businessKey);
        }
        leave.setInstanceId(started.payload());
        leaveRepository.save(leave);
        task.setStatus(LeaveStartTaskStatus.DONE);
        startTaskRepository.save(task);
        log.info("流程启动并回写完成：leaveId={} instanceId={} taskId={} businessKey={}",
                leave.getId(), leave.getInstanceId(), task.getId(), businessKey);
    }

    /**
     * 流程启动后置登记（afterCommit 事务同步，TenantUseCase 先例同款；无活跃事务同步时降级
     * 内联立即执行——framework-core BR-017 防丢失先例语义，不静默丢启动）。afterCommit 回调
     * 体内全捕获 RuntimeException（含 BizException/连接异常）：失败只写补偿+结构化 warn，
     * 异常绝不外抛（外抛会把 commit 后的响应变 5xx，违背「不落死态」意图）。
     */
    private void registerAfterCommitStart(LeaveRequest leave, LeaveStartTask startTask, String businessKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    startProcessWithCompensation(leave, startTask, businessKey);
                }
            });
            return;
        }
        // 无事务同步（直调/单测态）：内联立即执行
        startProcessWithCompensation(leave, startTask, businessKey);
    }

    /** 认领（PENDING→RUNNING 乐观锁 CAS）→ 启动+回写；失败登记补偿重试（全捕获，不外抛）。 */
    private void startProcessWithCompensation(LeaveRequest leave, LeaveStartTask startTask, String businessKey) {
        try {
            // 内联认领（乐观锁 CAS：insert 后版本=0；0 行更新=他人已认领——让位跳过，恰一执行者）
            startTask.setStatus(LeaveStartTaskStatus.RUNNING);
            startTaskRepository.save(startTask);
        } catch (RuntimeException ex) {
            log.warn("补偿任务认领失败（他人已认领，跳过启动）：taskId={} leaveId={}",
                    startTask.getId(), leave.getId(), ex);
            return;
        }
        try {
            startProcessAndWriteback(leave, startTask, businessKey);
        } catch (RuntimeException ex) {
            registerStartFailureQuietly(startTask, ex);
        }
    }

    /**
     * afterCommit 内联首败的补偿登记（缺省策略：退避 1s、上限 5 次，与重试任务缺省配置一致；
     * 配置面 acme.leave.start-retry-* 由 LeaveStartRetryJob 在重试轮次承担）。登记落库自身异常
     * 不外抛（保持 afterCommit 全捕获边界）——任务行留 RUNNING 留待租约回收自愈。
     */
    private void registerStartFailureQuietly(LeaveStartTask startTask, RuntimeException ex) {
        try {
            startTask.registerFailure(Instant.now(), DEFAULT_START_RETRY_BACKOFF_BASE_MS,
                    DEFAULT_START_RETRY_MAX_ATTEMPTS, failureSummary(ex));
            startTaskRepository.save(startTask);
        } catch (RuntimeException registrationEx) {
            log.warn("补偿任务失败登记落库异常（留待租约回收自愈）：taskId={}",
                    startTask.getId(), registrationEx);
        }
        log.warn("流程启动失败已登记补偿重试（响应不反噬）：leaveId={} taskId={} status={} attempts={} nextRetryAt={}",
                startTask.getLeaveId(), startTask.getId(), startTask.getStatus(),
                startTask.getAttempts(), startTask.getNextRetryAt(), ex);
    }

    /** 补偿登记错误摘要（类名+消息；leave_start_task.last_error 超宽由实体侧截断兜底）。 */
    private static String failureSummary(RuntimeException ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
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
