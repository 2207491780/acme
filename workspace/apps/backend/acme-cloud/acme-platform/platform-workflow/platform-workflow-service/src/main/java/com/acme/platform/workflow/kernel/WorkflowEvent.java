package com.acme.platform.workflow.kernel;

import java.time.Instant;
import java.util.Map;

/**
 * 流程领域事件（EventPublish SPI 载体——设计规格 §3.1/§7：实例启动/完成/终止、节点到达/离开、
 * 任务创建/完成；Kafka 事件流随 BPM-5 接入，topic acme.workflow.events）。
 *
 * <p>REQ-20261122-233425 确认④载荷补全：canonical 增补 traceId/definitionId/businessKey
 * （事件信封 WorkflowEventEnvelope 字段域），既有 7 参调用形态保留为兼容构造（traceId 经
 * TraceContext 在命令线程自动捕获——AFTER_COMMIT 异步发布线程无上下文，构造期捕获为准）。</p>
 *
 * @param eventType     事件类型（INSTANCE_STARTED/INSTANCE_COMPLETED/…）
 * @param tenantId      租户 id
 * @param traceId       链路追踪 id（命令线程捕获，可空）
 * @param definitionId  定义 id
 * @param definitionKey 流程键
 * @param instanceId    实例 id
 * @param nodeId        节点 id（实例级事件为 null）
 * @param businessKey   业务键（业务回写锚点，可空）
 * @param occurredAt    发生时刻
 * @param payload       事件明细（变量摘要等）
 */
public record WorkflowEvent(String eventType, String tenantId, String traceId, Long definitionId,
                            String definitionKey, Long instanceId, String nodeId, String businessKey,
                            Instant occurredAt, Map<String, Object> payload) {

    /** 兼容构造（既有 7 参调用形态；definitionId/businessKey 缺省，traceId 命令线程自动捕获）。 */
    public WorkflowEvent(String eventType, String tenantId, String definitionKey,
                         Long instanceId, String nodeId, Instant occurredAt,
                         Map<String, Object> payload) {
        this(eventType, tenantId, currentTraceId(), null, definitionKey, instanceId, nodeId,
                null, occurredAt, payload);
    }

    /** 载荷补全构造（BPM-5 事件流面：definitionId/businessKey 显式携带；traceId 命令线程自动捕获）。 */
    public WorkflowEvent(String eventType, String tenantId, Long definitionId, String definitionKey,
                         Long instanceId, String nodeId, String businessKey,
                         Instant occurredAt, Map<String, Object> payload) {
        this(eventType, tenantId, currentTraceId(), definitionId, definitionKey, instanceId, nodeId,
                businessKey, occurredAt, payload);
    }

    /** 命令线程 traceId 捕获（recordEvent 恒在命令事务线程构造，异步发布线程无上下文）。 */
    private static String currentTraceId() {
        com.acme.common.context.TraceContext trace = com.acme.common.context.TraceContext.get();
        return trace == null ? null : trace.traceId();
    }

    /** 事件类型常量（BPM-1 一期事件域）。 */
    public static final String INSTANCE_STARTED = "INSTANCE_STARTED";
    public static final String INSTANCE_COMPLETED = "INSTANCE_COMPLETED";
    public static final String INSTANCE_TERMINATED = "INSTANCE_TERMINATED";
    public static final String INSTANCE_SUSPENDED = "INSTANCE_SUSPENDED";
    public static final String INSTANCE_RESUMED = "INSTANCE_RESUMED";
    public static final String NODE_ENTERED = "NODE_ENTERED";
    public static final String NODE_LEFT = "NODE_LEFT";
    public static final String TASK_CREATED = "TASK_CREATED";
    public static final String TASK_COMPLETED = "TASK_COMPLETED";
    /** 任务驳回（BPM-5 增补埋点：业务侧驳回终态回写锚点——reject 不产生 TASK_COMPLETED）。 */
    public static final String TASK_REJECTED = "TASK_REJECTED";
    public static final String TIMER_FIRED = "TIMER_FIRED";
    public static final String SERVICE_TASK_FAILED = "SERVICE_TASK_FAILED";
}
