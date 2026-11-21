package com.acme.platform.workflow.kernel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * logging no-op 事件发布实现（REQ-20261121-142316 交付边界：Kafka 事件流归 BPM-5——framework-stream
 * 装配随其契约接入，本实现仅日志留痕，保证事件钩子链路在 MVP 即可观测）。
 */
@Component("loggingWorkflowEventPublisher")
public class LoggingWorkflowEventPublisher implements WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingWorkflowEventPublisher.class);

    @Override
    public void publish(WorkflowEvent event) {
        log.info("workflow-event type={} tenant={} definition={} instance={} node={} at={} payload={}",
                event.eventType(), event.tenantId(), event.definitionKey(), event.instanceId(),
                event.nodeId(), event.occurredAt(), event.payload());
    }
}
