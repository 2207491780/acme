package com.acme.common.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EventEnvelope 契约级语义测试（AC-005、架构设计 6.2）：信封构造与字段读取。
 */
class EventEnvelopeTest {

    @Test
    void constructAndReadFields() {
        EventEnvelope<String> envelope =
                new EventEnvelope<>("m1", "t1", "idem-1", "1.0", "payload");
        assertEquals("m1", envelope.getMessageId());
        assertEquals("t1", envelope.getTraceId());
        assertEquals("idem-1", envelope.getIdempotentKey());
        assertEquals("1.0", envelope.getVersion());
        assertEquals("payload", envelope.getPayload());
    }

    @Test
    void missingProtocolHeaderFailsExplicitly() {
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope<>(null, "t1", "idem-1", "1.0", "payload"));
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope<>("m1", "t1", null, "1.0", "payload"));
    }
}
