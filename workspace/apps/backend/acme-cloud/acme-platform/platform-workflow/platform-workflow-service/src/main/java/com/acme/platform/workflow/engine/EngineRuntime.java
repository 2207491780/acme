package com.acme.platform.workflow.engine;

import com.acme.common.core.Clock;
import com.acme.common.util.IdGenerator;
import com.acme.platform.workflow.compile.CompiledProcessModel;
import com.acme.platform.workflow.domain.WfExecution;
import com.acme.platform.workflow.domain.WfHistoryActivity;
import com.acme.platform.workflow.domain.WfProcessInstance;
import com.acme.platform.workflow.domain.WfTask;
import com.acme.platform.workflow.domain.WfTimerJob;
import com.acme.platform.workflow.kernel.CommandContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 引擎运行时（单命令内 token 驱动状态面）：编译模型 + 实例聚合 + 待推进 token 队列 +
 * 变量作用域工厂。行为（FlowNodeBehavior SPI）经本对象读写引擎状态；实体变更统一登记
 * CommandContext 持久化队列（单命令单事务，AC-12）。
 * <p>从属实体的 instance_id 由本运行时在持久化执行前统一回填（实例雪花 id 落库时分配——
 * 队列序 saveInstance 先行，回填时取值非空）；从属实体的主键在工厂内预分配（雪花 IdGenerator
 * 容器单例——子流程 scope 组合键/事件载荷需要落库前可用的实体 id）。</p>
 */
public final class EngineRuntime {

    private final CommandContext commandContext;
    private final CompiledProcessModel model;
    private final WfProcessInstance instance;
    private final String tenantId;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final VariableScope.PersistenceSink persistenceSink;
    private final IdGenerator idGenerator;
    private final Queue<WfExecution> queue = new ArrayDeque<>();
    private final List<Object> instanceBackfillEntities = new ArrayList<>();
    /** 库内变量快照（命令装载期全量拉取：name → 行；scope 组合键与根名行混存）。 */
    private final Map<String, com.acme.platform.workflow.domain.WfVariable> snapshot;
    /** 命令内写覆盖（scope 段名 → name → 值；跨执行视图共享）。 */
    private final Map<String, Map<String, Object>> overlay = new java.util.LinkedHashMap<>();
    /** 父执行解析器（scope 链派生——is_scope_root 行值权威）。 */
    private final java.util.function.Function<Long, java.util.Optional<WfExecution>> parentResolver;
    /** scope 链派生缓存（executionId → 链；同命令内父链稳定）。 */
    private final Map<Long, List<String>> scopeChainCache = new java.util.HashMap<>();

    public EngineRuntime(CommandContext commandContext, CompiledProcessModel model,
                         WfProcessInstance instance, String tenantId, Clock clock, JsonMapper jsonMapper,
                         VariableScope.PersistenceSink persistenceSink, IdGenerator idGenerator,
                         Map<String, com.acme.platform.workflow.domain.WfVariable> snapshot,
                         java.util.function.Function<Long, java.util.Optional<WfExecution>> parentResolver) {
        this.commandContext = commandContext;
        this.model = model;
        this.instance = instance;
        this.tenantId = tenantId;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.persistenceSink = persistenceSink;
        this.idGenerator = idGenerator;
        this.snapshot = snapshot;
        this.parentResolver = parentResolver;
    }

    public CommandContext commandContext() {
        return commandContext;
    }

    public CompiledProcessModel model() {
        return model;
    }

    public WfProcessInstance instance() {
        return instance;
    }

    public String tenantId() {
        return tenantId;
    }

    public Clock clock() {
        return clock;
    }

    public JsonMapper jsonMapper() {
        return jsonMapper;
    }

    /** 命令内统一时刻。 */
    public java.time.Instant now() {
        return commandContext.now();
    }

    /** 当前变量作用域——根视图（无执行上下文的变量面：候选解析等实例级规则）。 */
    public VariableScope variables() {
        return new VariableScope(instance.getId(), jsonMapper, persistenceSink, snapshot, overlay,
                List.of(VariableScope.ROOT_SEGMENT));
    }

    /**
     * execution 维度变量作用域（BPM-1 round-2 D1/D2）：scope 链按 execution 树派生
     * （is_scope_root 行值权威——父链上每个 scopeRoot 即一层 scope，近者先），跨命令/并行分支
     * 各自派生互不干扰，杜绝 runtime 级游标的跨命令丢失与并行互踩。
     */
    public VariableScope variablesFor(WfExecution execution) {
        List<String> chain = scopeChainCache.computeIfAbsent(execution.getId(), id -> deriveChain(execution));
        return new VariableScope(instance.getId(), jsonMapper, persistenceSink, snapshot, overlay, chain);
    }

    /** scope 链派生（近端 scopeRoot 先、根段收尾）：自身即 scope 根（is_scope_root=true，
     * 子流程域入口 execution 自持 token）时纳入链首——子流程 EndEvent 判定与域内变量遮蔽
     * 均以该行为权威（round-2 D1/D2 修正：旧实现漏计自身致子流程结束误判为实例终点）。 */
    private List<String> deriveChain(WfExecution execution) {
        List<String> chain = new ArrayList<>();
        if (Boolean.TRUE.equals(execution.getScopeRoot())) {
            chain.add(String.valueOf(execution.getId()));
        }
        WfExecution cursor = execution;
        java.util.Set<Long> visited = new java.util.HashSet<>();
        while (cursor.getParentId() != null && visited.add(cursor.getParentId())) {
            WfExecution parent = parentResolver.apply(cursor.getParentId()).orElse(null);
            if (parent == null) {
                break;
            }
            if (Boolean.TRUE.equals(parent.getScopeRoot())) {
                chain.add(String.valueOf(parent.getId()));
            }
            cursor = parent;
        }
        chain.add(VariableScope.ROOT_SEGMENT);
        return chain;
    }

    /** execution 的最近 scope 根 id（无子流程 scope 返回 null——回收面跳过根）。 */
    public Long scopeRootIdFor(WfExecution execution) {
        String nearest = variablesFor(execution).chainFirst();
        return VariableScope.ROOT_SEGMENT.equals(nearest) ? null : Long.valueOf(nearest);
    }

    /**
     * 回收子流程 scope（BPM-1 round-2 D1：按 scopeRoot 精确回收——根作用域静默跳过，
     * 重复回收幂等（D1 旧实现「根误判可回收抛 IllegalStateException」废除）；变量遮蔽行清理
     * ＋命令内覆盖清除。
     */
    public void collectSubProcessScope(Long scopeRootExecutionId) {
        if (scopeRootExecutionId == null) {
            return;
        }
        String segment = String.valueOf(scopeRootExecutionId);
        overlay.remove(segment);
        persistenceSink.deleteVariablesByPrefix(VariableScope.SCOPE_PREFIX + segment + ":");
    }

    /** token 入队（同命令事务内驱动；空转/已消费 token 由 drive 循环过滤）。 */
    public void enqueue(WfExecution execution) {
        queue.add(execution);
    }

    public Queue<WfExecution> queue() {
        return queue;
    }

    /** 领域事件登记。 */
    public void recordEvent(com.acme.platform.workflow.kernel.WorkflowEvent event) {
        commandContext.recordEvent(event);
    }

    /**
     * 持久化执行（直写语义）：变更在执行段即时落库（单命令单事务承载——命令失败整体回滚
     * AC-12 语义不变）；同命令内后续查询「读己之写」（并行 join 计数/全集取消面依赖），
     * 规避「队列待 flush + 读库快照」的乐观锁/孤儿行冲突。Persistence 拦截器位保留为
     * 事务收口（确认③链形不变）。
     */
    public void persist(Runnable operation) {
        backfillInstanceIds();
        operation.run();
    }

    /** 从属实体回填登记（工厂内创建即登记；落库面统一取实例 id——实例行先行落库）。 */
    public void trackForBackfill(Object entity) {
        instanceBackfillEntities.add(entity);
    }

    private void backfillInstanceIds() {
        if (instanceBackfillEntities.isEmpty()) {
            return;
        }
        Long instanceId = instance.getId();
        for (Object entity : instanceBackfillEntities) {
            if (entity instanceof WfExecution execution) {
                execution.setInstanceId(instanceId);
            } else if (entity instanceof WfTask task) {
                task.setInstanceId(instanceId);
            } else if (entity instanceof WfHistoryActivity history) {
                history.setInstanceId(instanceId);
            } else if (entity instanceof WfTimerJob timerJob) {
                timerJob.setInstanceId(instanceId);
            }
        }
        instanceBackfillEntities.clear();
    }

    /** 新执行实体工厂（主键预分配——scope 组合键/事件载荷需要落库前可用的实体 id）。 */
    public WfExecution newExecution(Long parentId, String nodeId, String nodeType,
                                    boolean active, boolean concurrent, boolean scopeRoot) {
        WfExecution execution = new WfExecution();
        execution.setId(idGenerator.nextId());
        execution.setTenantId(tenantId);
        execution.setParentId(parentId);
        execution.setNodeId(nodeId);
        execution.setNodeType(nodeType);
        execution.setActive(active);
        execution.setConcurrent(concurrent);
        execution.setScopeRoot(scopeRoot);
        trackForBackfill(execution);
        return execution;
    }

    /** 新任务实体工厂（主键预分配；TASK_CREATED 事件载荷需要落库前可用的任务 id）。 */
    public WfTaskHolder newTask(Long executionId, String nodeId, String nodeName,
                                String assignee) {
        com.acme.platform.workflow.domain.WfTask task = new com.acme.platform.workflow.domain.WfTask();
        task.setId(idGenerator.nextId());
        task.setTenantId(tenantId);
        task.setExecutionId(executionId);
        task.setNodeId(nodeId);
        task.setNodeName(nodeName);
        task.setAssignee(assignee);
        task.setStatus(com.acme.platform.workflow.api.WorkflowTaskStatus.CREATED);
        trackForBackfill(task);
        return new WfTaskHolder(task);
    }

    /** 历史行工厂。 */
    public com.acme.platform.workflow.domain.WfHistoryActivity newHistory(String nodeId, String nodeType,
                                                                          String action, String assignee,
                                                                          Map<String, Object> detail) {
        com.acme.platform.workflow.domain.WfHistoryActivity history =
                new com.acme.platform.workflow.domain.WfHistoryActivity();
        history.setId(idGenerator.nextId());
        history.setTenantId(tenantId);
        history.setNodeId(nodeId);
        history.setNodeType(nodeType);
        history.setAction(action);
        history.setAssignee(assignee);
        history.setStartAt(now());
        history.setDetailJson(detail == null ? null : jsonMapper.writeValueAsString(detail));
        trackForBackfill(history);
        return history;
    }

    /** 新任务便捷持有（创建即登记持久化，返回实体供行为继续装配）。 */
    public record WfTaskHolder(com.acme.platform.workflow.domain.WfTask task) {
    }
}
