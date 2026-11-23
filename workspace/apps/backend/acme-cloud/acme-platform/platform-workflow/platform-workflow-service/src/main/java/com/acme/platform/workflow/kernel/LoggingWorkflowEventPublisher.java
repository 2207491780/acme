package com.acme.platform.workflow.kernel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * logging no-op 事件发布实现（REQ-20261121-142316 引入；REQ-20261122-233425 确认④起 Kafka
 * 实现替换为缺省——本实现降级为兜底 bean：framework-stream 缺席（单体）/使用方显式注入
 * 自定义 WorkflowEventPublisher 时装配（WorkflowConfiguration 条件序），保证事件钩子链路
 * 在无 MQ 形态仍可观测）。
 */
public class LoggingWorkflowEventPublisher implements WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingWorkflowEventPublisher.class);

    @Override
    public void publish(WorkflowEvent event) {
        log.info("workflow-event type={} tenant={} definition={} instance={} node={} at={} payload={}",
                event.eventType(), event.tenantId(), event.definitionKey(), event.instanceId(),
                event.nodeId(), event.occurredAt(), event.payload());
    }
}
