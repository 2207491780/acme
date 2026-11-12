package com.acme.platform.infra.internal.consumer;

import com.acme.common.core.BizException;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.platform.infra.application.AuditIngestUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 审计事件消费函数测试：信封解析 → 落库调用；毒消息（协议字段缺失）显式失败（转死信路径）。
 */
class AuditEventConsumerTest {

    private final AuditIngestUseCase ingestUseCase = mock(AuditIngestUseCase.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    private Consumer<org.springframework.messaging.Message<String>> consumer() {
        return new AuditEventConsumer().auditEventIn(ingestUseCase, mapper);
    }

    @Test
    void parsesEnvelopeAndIngestsPayload() {
        AuditEvent payload = new AuditEvent("evt-1", "trace-1", "span-1", "default", "42", "B",
                "order.create", "order", "1", "下单", Instant.parse("2026-10-26T08:00:00Z"), "unit");
        String envelope = """
                {"messageId":"m-1","traceId":"trace-1","idempotentKey":"m-1","version":"v1",
                 "payload":{"eventId":"evt-1","traceId":"trace-1","spanId":"span-1","tenantId":"default",
                            "operatorId":"42","channel":"B","action":"order.create","targetType":"order",
                            "targetId":"1","content":"下单","occurredAt":"2026-10-26T08:00:00Z","source":"unit"}}
                """;
        consumer().accept(MessageBuilder.withPayload(envelope).build());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(ingestUseCase).ingest(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("evt-1");
        assertThat(captor.getValue().occurredAt()).isEqualTo(payload.occurredAt());
    }

    @Test
    void invalidEnvelopeFailsExplicitly() {
        assertThatThrownBy(() -> consumer().accept(
                        MessageBuilder.withPayload("{\"bad\":1}").build()))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(ingestUseCase);
    }

    @Test
    void blankMessageFailsExplicitly() {
        assertThatThrownBy(() -> consumer().accept(MessageBuilder.withPayload("  ").build()))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(ingestUseCase);
    }
}
