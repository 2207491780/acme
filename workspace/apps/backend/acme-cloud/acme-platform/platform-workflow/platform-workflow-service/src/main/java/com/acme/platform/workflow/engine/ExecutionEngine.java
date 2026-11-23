package com.acme.platform.workflow.engine;

import com.acme.platform.workflow.bpmn.AcmeAttributes;
import com.acme.platform.workflow.bpmn.BpmnElementType;
import com.acme.platform.workflow.compile.CompiledElement;
import com.acme.platform.workflow.compile.CompiledFlow;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.domain.WfTimerJob;
import com.acme.platform.workflow.infrastructure.client.ServiceTaskDelegateClient;
import com.acme.platform.workflow.infrastructure.repository.WfExecutionRepository;
import com.acme.platform.workflow.infrastructure.repository.WfProcessInstanceRepository;
import com.acme.platform.workflow.infrastructure.repository.WfTaskRepository;
import com.acme.platform.workflow.infrastructure.repository.WfTimerJobRepository;
import com.acme.platform.workflow.WorkflowError;
import com.acme.platform.workflow.kernel.WorkflowEvent;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 执行引擎（token 驱动内核——设计规格 §3.2 Execution 模型终稿）：
 * <ul>
 *   <li>drive 循环：token 队列逐个推进至 wait state（UserTask/ServiceTask）/ 实例终态 / 挂起；</li>
 *   <li>并行 fork/join：到达计数聚合（静态入边数=快照计数，禁动态加入）；</li>
 *   <li>子流程：execution 子树 scope + 变量遮蔽（读穿透写不穿透）+ 结束回收子树；</li>
 *   <li>历史链：ENTER/LEAVE 每次到达/离开成对（AC-15），驳回边/定时触发独立动作。</li>
 * </ul>
 * 实体变更统一登记 CommandContext 持久化队列（单命令单事务，AC-12）；行为经 EngineOperations
 * 推进 token（行为注册表与引擎无构造环）。
 */
@Component
public class ExecutionEngine implements EngineOperations {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final NodeBehaviorRegistry behaviorRegistry;
    private final AssignmentResolver assignmentResolver;
    private final HistoryRecorder historyRecorder;
    private final TimerScheduler timerScheduler;
    private final ServiceTaskDelegateClient delegateClient;
    private final WfExecutionRepository executionRepository;
    private final WfTaskRepository taskRepository;
    private final WfProcessInstanceRepository instanceRepository;
    private final WfTimerJobRepository timerJobRepository;
    private final com.acme.platform.workflow.config.WorkflowProperties properties;

    public ExecutionEngine(NodeBehaviorRegistry behaviorRegistry,
                           AssignmentResolver assignmentResolver,
                           HistoryRecorder historyRecorder,
                           TimerScheduler timerScheduler,
                           ServiceTaskDelegateClient delegateClient,
                           WfExecutionRepository executionRepository,
                           WfTaskRepository taskRepository,
                           WfProcessInstanceRepository instanceRepository,
                           WfTimerJobRepository timerJobRepository,
                           com.acme.platform.workflow.config.WorkflowProperties properties) {
        this.behaviorRegistry = behaviorRegistry;
        this.assignmentResolver = assignmentResolver;
        this.historyRecorder = historyRecorder;
        this.timerScheduler = timerScheduler;
        this.delegateClient = delegateClient;
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.timerJobRepository = timerJobRepository;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- 驱动循环

    /**
     * token 驱动循环（等待态停驱：UserTask/ServiceTask 到达即停；实例终态/挂起即停）。
     */
    public void drive(EngineRuntime runtime) {
        while (!runtime.queue().isEmpty()) {
            WfProcessInstance instance = runtime.instance();
            if (instance.getStatus() != com.acme.platform.workflow.api.WorkflowInstanceStatus.RUNNING) {
                break;
            }
            WfExecution execution = runtime.queue().poll();
            if (!Boolean.TRUE.equals(execution.getActive())) {
                continue;
            }
            CompiledElement element = runtime.model().element(execution.getNodeId());
            behaviorRegistry.of(element.type()).onEnter(runtime, execution);
        }
    }

    /** token 到达（node 定位 + ENTER 历史 + 入队）。 */
    public void arrive(EngineRuntime runtime, WfExecution execution, String nodeId) {
        CompiledElement element = runtime.model().element(nodeId);
        execution.setNodeId(nodeId);
        execution.setNodeType(element.type().name());
        execution.setActive(true);
        persistExecution(runtime, execution);
        historyRecorder.enter(runtime, nodeId, element.type().name(), null);
        runtime.enqueue(execution);
    }

    /**
     * execution 落库登记（直写语义——单命令单事务承载回滚，同命令内查询读己之写）。
     * instance_id 兜底回填（BPM-1 round-1 修复⑧）：工厂 backfill 覆盖新建实体；重载/跨命令
     * 路径的实体恒有行值——兜底仅在字段缺失时按运行时实例补齐，杜绝子树插入外键空值。
     */
    private void persistExecution(EngineRuntime runtime, WfExecution execution) {
        runtime.persist(() -> {
            if (execution.getInstanceId() == null) {
                execution.setInstanceId(runtime.instance().getId());
            }
            if (execution.getTenantId() == null) {
                execution.setTenantId(runtime.tenantId());
            }
            executionRepository.save(execution);
        });
    }

    // ---------------------------------------------------------------- EngineOperations

    @Override
    public void takeFlow(EngineRuntime runtime, WfExecution execution, CompiledFlow flow) {
        historyRecorder.leave(runtime, execution.getNodeId(), execution.getNodeType(), null, null);
        persistExecution(runtime, execution);
        arrive(runtime, execution, flow.targetRef());
    }

    @Override
    public void deadEndNoRoute(EngineRuntime runtime, WfExecution execution, String reason) {
        execution.setActive(false);
        persistExecution(runtime, execution);
        historyRecorder.leave(runtime, execution.getNodeId(), execution.getNodeType(), null,
                Map.of("noRoute", reason));
        log.warn("排他网关无路由（execution 挂起）：instance={} node={} reason={}",
                execution.getInstanceId(), execution.getNodeId(), reason);
        runtime.recordEvent(new WorkflowEvent(WorkflowEvent.INSTANCE_SUSPENDED, runtime.tenantId(),
                runtime.instance().getDefinitionId(), runtime.model().getProcessKey(),
                runtime.instance().getId(), execution.getNodeId(), runtime.instance().getBusinessKey(),
                runtime.now(), Map.of("reason", reason)));
    }

    @Override
    public void handleEndEvent(EngineRuntime runtime, WfExecution execution) {
        CompiledElement endNode = runtime.model().element(execution.getNodeId());
        // 子流程 scope 归属（round-2 D1/D2：scope 链按 execution 树派生——is_scope_root 行值权威；
        // 非 root 即处于子流程 scope 内）
        Long scopeRootId = runtime.scopeRootIdFor(execution);
        if (scopeRootId != null) {
            // 子流程 EndEvent：LEAVE 子流程 end → 回收子树 + scope 变量回收（scopeRoot 精确回收，
            // 根作用域静默跳过）→ 外层 LEAVE 子流程节点继续
            WfExecution scopeRoot = executionRepository.findById(scopeRootId)
                    .orElseThrow(() -> new IllegalStateException("子流程 scope 根缺失：" + scopeRootId));
            WfExecution outerExecution = scopeRoot.getParentId() == null ? scopeRoot
                    : executionRepository.findById(scopeRoot.getParentId()).orElse(scopeRoot);
            historyRecorder.leave(runtime, endNode.id(), endNode.type().name(), null, null);
            collectSubTree(runtime, outerExecution);
            runtime.collectSubProcessScope(scopeRootId);
            CompiledElement subNode = runtime.model().element(outerExecution.getNodeId());
            historyRecorder.leave(runtime, subNode.id(), subNode.type().name(), null, null);
            List<CompiledFlow> outgoing = runtime.model().outgoingOf(subNode.id());
            if (outgoing.isEmpty()) {
                completeInstance(runtime, outerExecution);
                return;
            }
            arrive(runtime, outerExecution, outgoing.get(0).targetRef());
            return;
        }
        // process EndEvent：实例 COMPLETED（其余在途 token/任务/定时统一取消）
        completeInstance(runtime, execution);
    }

    @Override
    public void handleParallelGateway(EngineRuntime runtime, WfExecution execution) {
        CompiledElement gateway = runtime.model().element(execution.getNodeId());
        List<CompiledFlow> outgoing = runtime.model().outgoingOf(gateway.id());
        List<CompiledFlow> incoming = runtime.model().incomingOf(gateway.id());
        boolean forkShape = incoming.size() <= 1 && outgoing.size() > 1;
        boolean joinShape = incoming.size() > 1 && outgoing.size() <= 1;
        if (forkShape) {
            // fork：parent execution 停驻 fork 节点，派生 N 并发子执行（§3.2）
            historyRecorder.leave(runtime, gateway.id(), gateway.type().name(), null, null);
            execution.setActive(false);
            persistExecution(runtime, execution);
            for (CompiledFlow branch : outgoing) {
                WfExecution child = runtime.newExecution(execution.getId(), branch.targetRef(),
                        runtime.model().element(branch.targetRef()).type().name(), false, true, false);
                persistExecution(runtime, child);
                arrive(runtime, child, branch.targetRef());
            }
            return;
        }
        if (joinShape) {
            int required = incoming.size();
            // FOR UPDATE 行锁串行化并发到达（BPM-1 round-1 修复⑦）：后到命令阻塞于前到命令的
            // 行锁直至其提交——计数读取恒见已提交一致态，杜绝双双读旧致聚合永不触发
            List<WfExecution> arrived = executionRepository
                    .findActiveByInstanceIdAndNodeIdForUpdate(execution.getInstanceId(), gateway.id()).stream()
                    .filter(candidate -> Objects.equals(candidate.getParentId(), execution.getParentId()))
                    .collect(Collectors.toList());
            if (arrived.size() < required) {
                // 部分到达：token 停驻 join 等聚合（AC-4：部分到达不推进；行锁随本事务提交释放）
                return;
            }
            // 最后到达者聚合：其余 child 标记 consumed（子执行物理删除），从 parent execution 继续（§3.2）
            runtime.persist(() -> executionRepository.forceDeleteByQuery(QueryWrapper.create()
                    .where(WfExecution::getInstanceId).eq(execution.getInstanceId())
                    .and(WfExecution::getParentId).eq(execution.getParentId())
                    .and(WfExecution::getNodeId).eq(gateway.id())));
            WfExecution parent = execution.getParentId() == null
                    ? execution : executionRepository.findById(execution.getParentId()).orElse(execution);
            parent.setActive(true);
            parent.setNodeId(gateway.id());
            parent.setNodeType(gateway.type().name());
            persistExecution(runtime, parent);
            historyRecorder.enter(runtime, gateway.id(), gateway.type().name(), null);
            historyRecorder.leave(runtime, gateway.id(), gateway.type().name(), null, null);
            persistExecution(runtime, parent);
            if (outgoing.isEmpty()) {
                completeInstance(runtime, parent);
                return;
            }
            arrive(runtime, parent, outgoing.get(0).targetRef());
            return;
        }
        // 1→1 形态（直通）：按流出推进
        takeFlow(runtime, execution, outgoing.get(0));
    }

    @Override
    public void createUserTask(EngineRuntime runtime, WfExecution execution) {
        CompiledElement node = runtime.model().element(execution.getNodeId());
        AssignmentResolver.Assignment assignment = assignmentResolver.resolve(
                node, runtime.variablesFor(execution), runtime.instance().getInitiator());
        WfTask task;
        if (assignment instanceof AssignmentResolver.Assignment.Fixed fixed) {
            task = runtime.newTask(execution.getId(), node.id(), node.name(), fixed.assignee()).task();
        } else {
            AssignmentResolver.Assignment.Candidates candidates =
                    (AssignmentResolver.Assignment.Candidates) assignment;
            task = runtime.newTask(execution.getId(), node.id(), node.name(), null).task();
            if (candidates.userIds().isEmpty()) {
                // 无候选人：任务保留（候选目录恢复后经 resume 可见）+ 实例挂起（AC-5 挂起语义）
                suspendInstance(runtime, WorkflowError.WORKFLOW_NO_ASSIGNEE_RESOLVED.code(),
                        "NO_ASSIGNEE_RESOLVED");
            }
        }
        WfTask created = task;
        // 事件随落库登记（任务雪花 id 落库后才有值；AFTER_COMMIT 异步发布）；instance_id 兜底回填（修复⑧同面）
        runtime.persist(() -> {
            if (created.getInstanceId() == null) {
                created.setInstanceId(runtime.instance().getId());
            }
            if (created.getTenantId() == null) {
                created.setTenantId(runtime.tenantId());
            }
            taskRepository.save(created);
            runtime.recordEvent(new WorkflowEvent(WorkflowEvent.TASK_CREATED, runtime.tenantId(),
                    runtime.instance().getDefinitionId(), runtime.model().getProcessKey(),
                    runtime.instance().getId(), node.id(), runtime.instance().getBusinessKey(),
                    runtime.now(), Map.of("taskId", String.valueOf(created.getId()))));
        });
        scheduleAttachedTimers(runtime, execution, node);
    }

    @Override
    public void dispatchServiceTask(EngineRuntime runtime, WfExecution execution, int attempt) {
        CompiledElement node = runtime.model().element(execution.getNodeId());
        String serviceRef = node.acme(AcmeAttributes.SERVICE_REF);
        String callbackPath = node.acme(AcmeAttributes.CALLBACK_PATH);
        long timeoutMs = parseLong(node.acme(AcmeAttributes.TIMEOUT_MS),
                properties.getDelegate().getTimeoutMs());
        var traceContext = com.acme.framework.trace.support.Traces.current();
        String traceId = traceContext == null
                ? com.acme.framework.trace.support.Traces.newTraceId()
                : traceContext.traceId();
        ServiceTaskDelegateClient.DelegationException failure = null;
        var payload = new com.acme.platform.workflow.api.dto.ServiceTaskDispatchPayload(
                execution.getId(), execution.getInstanceId(), runtime.instance().getBusinessKey(),
                runtime.tenantId(), node.id(), serviceRef, callbackPath,
                runtime.variablesFor(execution).rootSummary(), traceId, attempt);
        try {
            delegateClient.dispatch(payload, callbackPath, timeoutMs);
        } catch (ServiceTaskDelegateClient.DelegationException ex) {
            failure = ex;
        }
        if (failure != null) {
            recordDispatchFailure(runtime, node, attempt, failure.getMessage());
            int maxAttempts = (int) parseLong(node.acme(AcmeAttributes.RETRY_MAX_ATTEMPTS),
                    properties.getDelegate().getRetryMaxAttempts());
            if (attempt >= maxAttempts) {
                suspendInstance(runtime, WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT.code(),
                        "SERVICE_TASK_FAILED");
                return;
            }
            long backoffMs = parseLong(node.acme(AcmeAttributes.RETRY_BACKOFF_MS),
                    properties.getDelegate().getRetryBackoffMs());
            timerScheduler.scheduleServiceTaskRetry(execution.getId(), attempt + 1, backoffMs);
        }
        // 下发成功：token 停驻等待回调（wait state——drive 循环自然停驱）
    }

    @Override
    public void enterSubProcess(EngineRuntime runtime, WfExecution execution) {
        CompiledElement subProcess = runtime.model().element(execution.getNodeId());
        // 外层 execution 停驻子流程节点（结束/边界中断恢复锚点）
        execution.setActive(false);
        persistExecution(runtime, execution);
        // scope 根执行 + 变量遮蔽（读穿透写不穿透）——scope 链由 execution 树派生（round-2 D1/D2），
        // 无需游标 enter/exit
        WfExecution scopeRoot = runtime.newExecution(execution.getId(), subProcess.id(),
                subProcess.type().name(), true, false, true);
        persistExecution(runtime, scopeRoot);
        scheduleAttachedTimers(runtime, scopeRoot, subProcess);
        String subStartId = runtime.model().getElements().values().stream()
                .filter(candidate -> candidate.type() == BpmnElementType.START_EVENT
                        && subProcess.id().equals(candidate.containerId()))
                .map(CompiledElement::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("子流程缺 StartEvent：" + subProcess.id()));
        arrive(runtime, scopeRoot, subStartId);
    }

    // ---------------------------------------------------------------- 命令面操作

    /** 办结离开任务节点（任务 COMPLETED 已由命令落定；注销挂接定时器 + 沿流出推进）。 */
    public void leaveTaskNode(EngineRuntime runtime, WfExecution execution, Map<String, Object> detail) {
        timerScheduler.cancelTimersForNode(runtime, execution.getNodeId());
        historyRecorder.leave(runtime, execution.getNodeId(), execution.getNodeType(), null, detail);
        persistExecution(runtime, execution);
        List<CompiledFlow> outgoing = runtime.model().outgoingOf(execution.getNodeId());
        if (outgoing.isEmpty()) {
            completeInstance(runtime, execution);
            return;
        }
        arrive(runtime, execution, outgoing.get(0).targetRef());
    }

    /**
     * 驳回回退（确认⑤：targetNodeId=上一 UserTask/StartEvent——REJECT_EDGE 已由命令落定；
     * 注销挂接定时器 + token 直达回退目标节点重开新任务实例，历史链保留）。
     */
    public void rejectToNode(EngineRuntime runtime, WfExecution execution, String targetNodeId,
                             Map<String, Object> detail) {
        timerScheduler.cancelTimersForNode(runtime, execution.getNodeId());
        historyRecorder.leave(runtime, execution.getNodeId(), execution.getNodeType(), null, detail);
        persistExecution(runtime, execution);
        arrive(runtime, execution, targetNodeId);
    }

    /** 实例正常终止（EndEvent 到达）。 */
    public void completeInstance(EngineRuntime runtime, WfExecution atEndExecution) {
        WfProcessInstance instance = runtime.instance();
        historyRecorder.leave(runtime, atEndExecution.getNodeId(), atEndExecution.getNodeType(),
                null, null);
        atEndExecution.setActive(false);
        persistExecution(runtime, atEndExecution);
        instance.setStatus(com.acme.platform.workflow.api.WorkflowInstanceStatus.COMPLETED);
        instance.setEndTime(runtime.now());
        runtime.persist(() -> instanceRepository.save(instance));
        cancelAllActiveWork(runtime);
        runtime.recordEvent(new WorkflowEvent(WorkflowEvent.INSTANCE_COMPLETED, runtime.tenantId(),
                instance.getDefinitionId(), runtime.model().getProcessKey(), instance.getId(),
                atEndExecution.getNodeId(), instance.getBusinessKey(),
                runtime.now(), Map.of()));
    }

    /** 实例终止（terminate：TERMINATED + 未完成 execution/task 全 CANCELLED——确认⑤撤回语义）。 */
    public void terminateInstance(EngineRuntime runtime) {
        WfProcessInstance instance = runtime.instance();
        instance.setStatus(com.acme.platform.workflow.api.WorkflowInstanceStatus.TERMINATED);
        instance.setEndTime(runtime.now());
        runtime.persist(() -> instanceRepository.save(instance));
        cancelAllActiveWork(runtime);
        runtime.recordEvent(new WorkflowEvent(WorkflowEvent.INSTANCE_TERMINATED, runtime.tenantId(),
                instance.getDefinitionId(), runtime.model().getProcessKey(), instance.getId(),
                null, instance.getBusinessKey(), runtime.now(), Map.of()));
    }

    /** 实例挂起（NO_ASSIGNEE_RESOLVED / SERVICE_TASK_FAILED / 运维挂起共用面）。 */
    public void suspendInstance(EngineRuntime runtime, String errorCodeAnchor, String reason) {
        WfProcessInstance instance = runtime.instance();
        instance.setStatus(com.acme.platform.workflow.api.WorkflowInstanceStatus.SUSPENDED);
        instance.setSuspendReason(reason);
        runtime.persist(() -> instanceRepository.save(instance));
        runtime.recordEvent(new WorkflowEvent(WorkflowEvent.INSTANCE_SUSPENDED, runtime.tenantId(),
                instance.getDefinitionId(), runtime.model().getProcessKey(), instance.getId(),
                null, instance.getBusinessKey(), runtime.now(),
                Map.of("reason", reason, "errorCode", errorCodeAnchor)));
    }

    /** 活跃执行/任务/定时全集取消（terminate/completed 面；直写语义——查询见当前状态一致面）。 */
    private void cancelAllActiveWork(EngineRuntime runtime) {
        Long instanceId = runtime.instance().getId();
        for (WfExecution execution : executionRepository.findActiveByInstanceId(instanceId)) {
            execution.setActive(false);
            persistExecution(runtime, execution);
        }
        for (WfTask task : taskRepository.findActiveByInstanceId(instanceId)) {
            task.setStatus(com.acme.platform.workflow.api.WorkflowTaskStatus.CANCELLED);
            runtime.persist(() -> taskRepository.save(task));
        }
        timerScheduler.cancelAllForInstance(runtime);
    }

    /**
     * 定时边界事件触发（确认⑥＋round-2 D4：原子 CAS PENDING→FIRED 为权威抢占面——
     * fire 端点与本地兜底双通道统一；0 行=已被并发通道消费，BizException 由兜底面静默跳过、
     * fire 端点面 409 拒绝；抢占成功才中断当前任务走边界分支）。
     */
    public void fireTimer(EngineRuntime runtime, WfTimerJob timerJob) {
        int claimed = timerJobRepository.casFire(timerJob.getId());
        if (claimed == 0) {
            throw new com.acme.common.core.BizException(WorkflowError.WORKFLOW_INSTANCE_STATE_CONFLICT,
                    "定时器已离开 PENDING（重复 fire 拒绝）：" + timerJob.getId());
        }
        timerJob.setStatus(WfTimerJob.STATUS_FIRED);
        CompiledElement boundary = runtime.model().element(timerJob.getBoundaryEventId());
        historyRecorder.timerFire(runtime, boundary.id(), Map.of(
                "timerJobId", String.valueOf(timerJob.getId()),
                "hostNodeId", timerJob.getNodeId()));
        runtime.recordEvent(new WorkflowEvent(WorkflowEvent.TIMER_FIRED, runtime.tenantId(),
                runtime.instance().getDefinitionId(), runtime.model().getProcessKey(),
                runtime.instance().getId(), boundary.id(), runtime.instance().getBusinessKey(),
                runtime.now(), Map.of("hostNodeId", timerJob.getNodeId())));
        interruptHost(runtime, boundary, timerJob);
    }

    /** 中断挂接节点（中断型语义：任务 CANCELLED / 子树回收 → 边界流出分支推进）。 */
    private void interruptHost(EngineRuntime runtime, CompiledElement boundary, WfTimerJob timerJob) {
        CompiledElement host = runtime.model().element(timerJob.getNodeId());
        if (host.type() == BpmnElementType.USER_TASK) {
            for (WfTask task : taskRepository.findActiveByInstanceId(runtime.instance().getId())) {
                if (!host.id().equals(task.getNodeId())) {
                    continue;
                }
                task.setStatus(com.acme.platform.workflow.api.WorkflowTaskStatus.CANCELLED);
                runtime.persist(() -> taskRepository.save(task));
                WfExecution tokenExecution = executionRepository.findById(task.getExecutionId())
                        .orElse(null);
                if (tokenExecution == null || !Boolean.TRUE.equals(tokenExecution.getActive())) {
                    continue;
                }
                historyRecorder.leave(runtime, host.id(), host.type().name(), task.getAssignee(),
                        Map.of("interruptedBy", boundary.id()));
                timerScheduler.cancelTimersForNode(runtime, host.id());
                leaveBoundary(runtime, tokenExecution, boundary);
            }
            return;
        }
        if (host.type() == BpmnElementType.EMBEDDED_SUB_PROCESS) {
            // 外层停驻 execution 定位 + 子树回收 + scope 变量回收（scopeRoot=外层直接子执行的 scope 根）
            WfExecution outerExecution = executionRepository
                    .findActiveByInstanceId(runtime.instance().getId()).stream()
                    .filter(candidate -> host.id().equals(candidate.getNodeId()))
                    .findFirst()
                    .orElse(null);
            if (outerExecution != null) {
                Long scopeRootId = executionRepository.findByParentId(outerExecution.getId()).stream()
                        .filter(child -> Boolean.TRUE.equals(child.getScopeRoot()))
                        .map(WfExecution::getId)
                        .findFirst().orElse(null);
                collectSubTree(runtime, outerExecution);
                runtime.collectSubProcessScope(scopeRootId);
                historyRecorder.leave(runtime, host.id(), host.type().name(), null,
                        Map.of("interruptedBy", boundary.id()));
                leaveBoundary(runtime, outerExecution, boundary);
            }
        }
    }

    /** 边界事件离开（边界节点 ENTER/LEAVE 成对后沿流出推进；token 只入队一次——
     *  interruptHost 原「先 arrive(boundary) 再 leaveBoundary(target)」对同一 execution 双入队，
     *  驱动循环两次命中目标节点，重复创建边界分支任务）。 */
    private void leaveBoundary(EngineRuntime runtime, WfExecution execution, CompiledElement boundary) {
        historyRecorder.enter(runtime, boundary.id(), boundary.type().name(), null);
        historyRecorder.leave(runtime, boundary.id(), boundary.type().name(), null, null);
        persistExecution(runtime, execution);
        List<CompiledFlow> outgoing = runtime.model().outgoingOf(boundary.id());
        if (outgoing.isEmpty()) {
            deadEndNoRoute(runtime, execution, "boundary-out-empty");
            return;
        }
        arrive(runtime, execution, outgoing.get(0).targetRef());
    }

    /** 子树回收（scope 根及其后代物理删除；子孙活跃任务 CANCELLED——§3.2「子流程结束回收子树」）。 */
    private void collectSubTree(EngineRuntime runtime, WfExecution outerExecution) {
        Long instanceId = runtime.instance().getId();
        List<WfExecution> all = executionRepository.findByInstanceId(instanceId);
        Map<Long, List<WfExecution>> byParent = new HashMap<>();
        for (WfExecution candidate : all) {
            if (candidate.getParentId() != null) {
                byParent.computeIfAbsent(candidate.getParentId(), k -> new ArrayList<>()).add(candidate);
            }
        }
        List<Long> doomed = new ArrayList<>();
        collectDescendants(outerExecution.getId(), byParent, doomed);
        if (doomed.isEmpty()) {
            return;
        }
        for (WfExecution descendant : all) {
            if (!doomed.contains(descendant.getId())) {
                continue;
            }
            for (WfTask task : taskRepository.findByExecutionId(descendant.getId())) {
                if (task.getStatus() == com.acme.platform.workflow.api.WorkflowTaskStatus.CREATED
                        || task.getStatus() == com.acme.platform.workflow.api.WorkflowTaskStatus.CLAIMED) {
                    task.setStatus(com.acme.platform.workflow.api.WorkflowTaskStatus.CANCELLED);
                    runtime.persist(() -> taskRepository.save(task));
                }
            }
        }
        runtime.persist(() -> executionRepository.forceDeleteByQuery(QueryWrapper.create()
                .where(WfExecution::getInstanceId).eq(instanceId)
                .and(WfExecution::getId).in(doomed)));
    }

    private static void collectDescendants(Long parentId, Map<Long, List<WfExecution>> byParent,
                                           List<Long> doomed) {
        for (WfExecution child : byParent.getOrDefault(parentId, List.of())) {
            doomed.add(child.getId());
            collectDescendants(child.getId(), byParent, doomed);
        }
    }

    /** 挂接定时器排期（UserTask/子流程到达面）。 */
    private void scheduleAttachedTimers(EngineRuntime runtime, WfExecution execution,
                                        CompiledElement node) {
        for (CompiledElement boundary : runtime.model().attachedTimersOf(node.id())) {
            Instant dueAt = boundary.timerDelayMs() != null
                    ? runtime.now().plusMillis(boundary.timerDelayMs())
                    : Instant.parse(boundary.timerDate());
            timerScheduler.scheduleBoundaryTimer(runtime, execution, boundary, dueAt);
        }
    }

    /** 下发失败明细（历史 LEAVE detail 承载重试轮次；告警事件同步登记）。 */
    private void recordDispatchFailure(EngineRuntime runtime, CompiledElement node, int attempt,
                                       String error) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("serviceTask", node.id());
        detail.put("attempt", attempt);
        detail.put("error", error);
        historyRecorder.record(runtime, node.id(), node.type().name(),
                com.acme.platform.workflow.api.dto.HistoryActivityView.ACTION_LEAVE, null, detail);
        runtime.recordEvent(new WorkflowEvent(WorkflowEvent.SERVICE_TASK_FAILED, runtime.tenantId(),
                runtime.instance().getDefinitionId(), runtime.model().getProcessKey(),
                runtime.instance().getId(), node.id(), runtime.instance().getBusinessKey(),
                runtime.now(), detail));
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
