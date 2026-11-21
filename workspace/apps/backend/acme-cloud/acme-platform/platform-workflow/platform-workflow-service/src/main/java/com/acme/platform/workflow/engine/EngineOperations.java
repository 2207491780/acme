package com.acme.platform.workflow.engine;

import com.acme.platform.workflow.compile.CompiledFlow;
import com.acme.platform.workflow.domain.WfExecution;

/**
 * 引擎操作面（行为 ↔ 引擎解耦缝：FlowNodeBehavior 经本接口推进 token，避免行为注册表与
 * 引擎单例的构造环——行为侧 ObjectProvider 惰性解析）。全部操作在当前命令事务内（单命令单事务）。
 */
public interface EngineOperations {

    /** 沿流出连线推进（LEAVE 当前节点 → arrive 目标节点 → 入驱动队列）。 */
    void takeFlow(EngineRuntime runtime, WfExecution execution, CompiledFlow flow);

    /** 无路可走挂起该 execution（排他网关无真无 default——设计规格 §2；token 置 inactive）。 */
    void deadEndNoRoute(EngineRuntime runtime, WfExecution execution, String reason);

    /** 到达 EndEvent（容器级：process 终止实例 / 子流程结束回收子树后自外层继续）。 */
    void handleEndEvent(EngineRuntime runtime, WfExecution execution);

    /** 并行网关 fork/join（§3.2：fork 派生并发子执行；join 到达计数聚合最后一个继续）。 */
    void handleParallelGateway(EngineRuntime runtime, WfExecution execution);

    /** UserTask 到达（指派解析 → 任务创建 + 挂接定时器；无候选 → 实例 NO_ASSIGNEE_RESOLVED 挂起）。 */
    void createUserTask(EngineRuntime runtime, WfExecution execution);

    /** ServiceTask 到达/重试轮次（HTTP 委托下发；失败按重试上限挂起 SERVICE_TASK_FAILED）。 */
    void dispatchServiceTask(EngineRuntime runtime, WfExecution execution, int attempt);

    /** EmbeddedSubProcess 到达（scope 根执行 + 变量遮蔽 + 挂接定时器 + 驱动子流程 StartEvent）。 */
    void enterSubProcess(EngineRuntime runtime, WfExecution execution);
}
