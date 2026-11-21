package com.acme.platform.workflow.kernel;

import java.time.Instant;
import java.util.Map;

/**
 * 流程领域事件（EventPublish SPI 载体——设计规格 §3.1/§7：实例启动/完成/终止、节点到达/离开、
 * 任务创建/完成；一期 logging no-op，Kafka topic acme-workflow-events 归 BPM-5）。
 *
 * @param eventType     事件类型（INSTANCE_STARTED/INSTANCE_COMPLETED/…）
 * @param tenantId      租户 id
 * @param definitionKey 流程键
 * @param instanceId    实例 id
 * @param nodeId        节点 id（实例级事件为 null）
 * @param occurredAt    发生时刻
 * @param payload       事件明细（变量摘要等）
 */
public record WorkflowEvent(String eventType, String tenantId, String definitionKey,
                            Long instanceId, String nodeId, Instant occurredAt,
                            Map<String, Object> payload) {

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
    public static final String TIMER_FIRED = "TIMER_FIRED";
    public static final String SERVICE_TASK_FAILED = "SERVICE_TASK_FAILED";
}
