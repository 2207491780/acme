package com.acme.platform.workflow.bpmn;

import java.util.Set;

/**
 * BPMN 2.0 一期语义白名单元素类型（设计规格 §2 终稿；CallActivity/InclusiveGateway/多实例/
 * Message/Signal/EventSubProcess/补偿 归二期 BPM-6——出现在 XML 中按校验错误逐条返回）。
 * nodeType 列名取枚举 name（wf_execution.node_type / wf_history_activity.node_type / 编译模型键）。
 */
public enum BpmnElementType {

    /** none 类型启动事件（每容器恰一）。 */
    START_EVENT,

    /** none 类型结束事件（到达即实例 COMPLETED）。 */
    END_EVENT,

    /** 排他网关（XOR：首个为真流出；无真走 default；无 default 挂起该 execution）。 */
    EXCLUSIVE_GATEWAY,

    /** 并行网关（AND：fork N 并发 / join 到达计数聚合）。 */
    PARALLEL_GATEWAY,

    /** 用户任务（指派规则 + complete/reject/claim/unclaim/transfer/delegate）。 */
    USER_TASK,

    /** 服务任务（HTTP 委托 + callback 推进）。 */
    SERVICE_TASK,

    /** 嵌入子流程（独立 scope + 变量遮蔽）。 */
    EMBEDDED_SUB_PROCESS,

    /** 定时边界事件（中断型，挂 UserTask/EmbeddedSubProcess）。 */
    BOUNDARY_TIMER;

    /** 非节点连线上不可驻留的类型（SequenceFlow 走独立 CompiledFlow 模型）。 */
    public static final Set<String> WAIT_STATE_TYPES = Set.of(USER_TASK.name(), SERVICE_TASK.name());
}
