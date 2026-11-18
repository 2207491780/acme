package com.acme.platform.infra.internal.mq;

import com.acme.common.contract.EventEnvelope;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.stream.support.MessageSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 审计事件 MQ 桥接测试：信封契约（version=v1 + payload 透传）与 stream 关闭降级（BR-013）。
 */
class AuditEventMqSinkTest {

    private final MessageSender sender = mock(MessageSender.class);

    @Test
    void sendsEnvelopeWithV1Version() {
        AuditEvent event = new AuditEvent("evt-1", "t", "s", "default", "42", "B",
                "order.create", "order", "1", "下单", Instant.now(), "unit");
        new AuditEventMqSink(sender).onEvent(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<AuditEvent>> captor =
                ArgumentCaptor.forClass((Class) EventEnvelope.class);
        verify(sender).send(org.mockito.ArgumentMatchers.eq(AuditEventMqSink.OUT_BINDING), captor.capture());
        EventEnvelope<AuditEvent> envelope = captor.getValue();
        assertThat(envelope.getVersion()).isEqualTo("v1");
        assertThat(envelope.getPayload().eventId()).isEqualTo("evt-1");
        assertThat(envelope.getIdempotentKey()).isNotBlank();
    }

    @Test
    void degradesToNoopWhenStreamDisabled() {
        new AuditEventMqSink(null).onEvent(new AuditEvent("evt-2", null, null, null,
                null, null, "x.y", null, null, null, Instant.now(), "unit"));
        verifyNoInteractions(sender);
    }
}
