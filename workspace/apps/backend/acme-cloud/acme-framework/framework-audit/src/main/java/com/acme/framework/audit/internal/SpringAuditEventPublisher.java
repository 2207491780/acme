package com.acme.framework.audit.internal;

import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditEventPublisher;
import com.acme.framework.audit.support.AuditEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

/**
 * 默认审计事件出口（FR-009、架构设计 8.4）：Spring 事件广播（本期唯一默认通道）
 * + {@link AuditEventSink} SPI 转发；发布失败 ERROR 兜底、不阻断业务（BR-013）。
 */
public class SpringAuditEventPublisher implements AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpringAuditEventPublisher.class);

    private final ApplicationEventPublisher publisher;
    private final List<AuditEventSink> sinks;

    public SpringAuditEventPublisher(ApplicationEventPublisher publisher, List<AuditEventSink> sinks) {
        this.publisher = publisher;
        this.sinks = sinks == null ? List.of() : sinks;
    }

    @Override
    public void publish(AuditEvent event) {
        publisher.publishEvent(event);
        for (AuditEventSink sink : sinks) {
            try {
                sink.onEvent(event);
            } catch (RuntimeException ex) {
                log.error("审计事件桥接失败（不影响业务）: {}", ex.getMessage());
            }
        }
    }
}
