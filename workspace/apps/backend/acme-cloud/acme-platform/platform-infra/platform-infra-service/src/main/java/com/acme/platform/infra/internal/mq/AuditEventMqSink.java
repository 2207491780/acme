package com.acme.platform.infra.internal.mq;

import com.acme.common.contract.EventEnvelope;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditEventSink;
import com.acme.framework.stream.support.EventEnvelopes;
import com.acme.framework.stream.support.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计事件 MQ 桥接（AuditEventSink SPI 的 MQ 通道实现，Q-005 后续特性）：
 * 审计埋点（Spring 事件出口）→ EventEnvelope{version=v1} → 主题 infra-audit-events；
 * 本类即「审计事件桥接约定」的参考实现（≥3 服务需要桥接时按 Modules.md 2.3 准入判据下沉 framework）。
 * stream 关闭（MessageSender 缺席）时降级为 WARN 空操作——埋点失败不阻断业务（BR-013）。
 */
public class AuditEventMqSink implements AuditEventSink {

    /** 审计事件出站绑定与信封版本（MQ 契约：主题 infra-audit-events，13.2.3 事件定义归发布方）。 */
    public static final String OUT_BINDING = "auditEventOut-out-0";
    public static final String ENVELOPE_VERSION = "v1";

    private static final Logger log = LoggerFactory.getLogger(AuditEventMqSink.class);

    private final MessageSender sender;

    public AuditEventMqSink(MessageSender sender) {
        this.sender = sender;
    }

    @Override
    public void onEvent(AuditEvent event) {
        if (sender == null) {
            log.warn("审计事件 MQ 桥接不可用（stream 未启用），事件丢弃（BR-013 不阻断业务）：eventId={}",
                    event == null ? null : event.eventId());
            return;
        }
        EventEnvelope<AuditEvent> envelope = EventEnvelopes.newEnvelope(event, ENVELOPE_VERSION);
        sender.send(OUT_BINDING, envelope);
    }
}
