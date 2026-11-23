package com.acme.platform.workflow.api.dto;

import java.time.Instant;

/**
 * 流程领域事件载荷（framework-stream EventEnvelopes 信封的 payload 形态；REQ-20261122-233425
 * 确认④：topic=acme.workflow.events，消费方按本类型类型化反序列化——EventEnvelopes.parse(json,
 * WorkflowEventEnvelope.class, mapper)）。
 *
 * <p>发布面=KafkaWorkflowEventPublisher（WorkflowEventPublisher SPI 的 Kafka 实现，AFTER_COMMIT
 * 异步）；既有埋点全覆盖（TASK_CREATED/INSTANCE_COMPLETED/INSTANCE_TERMINATED/INSTANCE_SUSPENDED/
 * TIMER_FIRED/SERVICE_TASK_FAILED/INSTANCE_STARTED），实例级事件 nodeId 为空。</p>
 *
 * @param eventType     事件类型（WorkflowEvent 常量域）
 * @param instanceId    流程实例 id
 * @param definitionId  定义 id
 * @param definitionKey 定义键
 * @param nodeId        节点 id（实例级事件为空）
 * @param businessKey   业务键（业务回写锚点——如 leave 按 businessKey 回写终态）
 * @param tenantId      租户 id
 * @param traceId       链路追踪 id（命令线程捕获）
 * @param occurredAt    发生时刻
 */
public record WorkflowEventEnvelope(String eventType, Long instanceId, Long definitionId,
                                    String definitionKey, String nodeId, String businessKey,
                                    String tenantId, String traceId, Instant occurredAt) {
}
