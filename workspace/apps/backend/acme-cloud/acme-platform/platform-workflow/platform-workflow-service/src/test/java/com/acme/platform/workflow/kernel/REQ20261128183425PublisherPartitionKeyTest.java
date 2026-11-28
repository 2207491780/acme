package com.acme.platform.workflow.kernel;

import com.acme.framework.stream.support.MessageSender;
import com.acme.platform.workflow.api.dto.WorkflowEventEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Kafka 事件发布器分区键头注入（REQ-20261128-183425 P2-12 AC-2）：instanceId 非空注入
 * {@code partitionKey=instance-<id>}（workflow yml messageKeyExpression 消费，同实例事件
 * 同分区保序）；instanceId 空不注入头（发布仍完成，路由面无键形态）。发布走专属单线程
 * executor——verify 用 {@code timeout()} 等待异步交互落盘。
 */
class REQ20261128183425PublisherPartitionKeyTest {

    private static final long ASYNC_WAIT_MS = 1_000;

    private final MessageSender messageSender = mock(MessageSender.class);
    private final KafkaWorkflowEventPublisher publisher = new KafkaWorkflowEventPublisher(messageSender);

    private static WorkflowEvent event(Long instanceId) {
        return new WorkflowEvent(WorkflowEvent.INSTANCE_COMPLETED, "default", "trace-1", 7L, "leave",
                instanceId, null, "LEAVE-1", Instant.parse("2026-11-28T10:00:00Z"), Map.of());
    }

    @Test
    void injectsPartitionKeyHeaderWhenInstanceIdPresent() {
        publisher.publish(event(9001L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.forClass(Map.class);
        verify(messageSender, timeout(ASYNC_WAIT_MS)).send(eq(KafkaWorkflowEventPublisher.BINDING),
                any(WorkflowEventEnvelope.class), eq(KafkaWorkflowEventPublisher.ENVELOPE_VERSION),
                headers.capture());
        assertThat(headers.getValue())
                .containsEntry(KafkaWorkflowEventPublisher.PARTITION_KEY_HEADER, "instance-9001");
    }

    @Test
    void omitsPartitionKeyHeaderWhenInstanceIdAbsent() {
        publisher.publish(event(null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.forClass(Map.class);
        verify(messageSender, timeout(ASYNC_WAIT_MS)).send(eq(KafkaWorkflowEventPublisher.BINDING),
                any(WorkflowEventEnvelope.class), eq(KafkaWorkflowEventPublisher.ENVELOPE_VERSION),
                headers.capture());
        assertThat(headers.getValue()).isNull();
    }

    @Test
    void headerNameIsStableContractForBindingConfig() {
        // yml 单源消费（messageKeyExpression=headers['partitionKey']）与发布面常量一致性
        assertThat(KafkaWorkflowEventPublisher.PARTITION_KEY_HEADER).isEqualTo("partitionKey");
        verifyNoInteractions(messageSender);
    }
}
