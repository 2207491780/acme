package com.acme.framework.stream.support;

import com.acme.common.contract.EventEnvelope;
import com.acme.common.core.BizException;
import com.acme.common.serialization.JsonModules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 信封辅助（DP-009/DP-015：缺省信封口径、消费侧类型化解析、失败统一 ENVELOPE_INVALID）。
 */
class EventEnvelopesTest {

    private final JsonMapper mapper;

    EventEnvelopesTest() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        this.mapper = builder.build();
    }

    record DemoPayload(String name) {
    }

    @Test
    void newEnvelopeDefaultsIdempotentKeyToMessageId() {
        EventEnvelope<DemoPayload> envelope =
                EventEnvelopes.newEnvelope(new DemoPayload("n"), "v1");
        assertThat(envelope.getIdempotentKey()).isEqualTo(envelope.getMessageId());
        assertThat(envelope.getTraceId()).isNotBlank();
        assertThat(envelope.getVersion()).isEqualTo("v1");
    }

    @Test
    void parseRoundTripsTypedPayload() {
        String json = """
                {"messageId":"m-1","traceId":"t-1","idempotentKey":"i-1","version":"v1","payload":{"name":"alpha"}}
                """;
        EventEnvelope<DemoPayload> parsed =
                EventEnvelopes.parse(json, DemoPayload.class, mapper);
        assertThat(parsed.getMessageId()).isEqualTo("m-1");
        assertThat(parsed.getTraceId()).isEqualTo("t-1");
        assertThat(parsed.getIdempotentKey()).isEqualTo("i-1");
        assertThat(parsed.getPayload()).isEqualTo(new DemoPayload("alpha"));
    }

    @Test
    void parseAllowsNullPayload() {
        String json = """
                {"messageId":"m-2","traceId":"t-2","idempotentKey":"i-2","version":"v1","payload":null}
                """;
        EventEnvelope<Object> parsed = EventEnvelopes.parse(json, Object.class, mapper);
        assertThat(parsed.getPayload()).isNull();
    }

    @Test
    void parseRejectsBlankProtocolFields() {
        String json = """
                {"messageId":"","traceId":"t-1","idempotentKey":"i-1","version":"v1","payload":null}
                """;
        assertThatThrownBy(() -> EventEnvelopes.parse(json, Object.class, mapper))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(StreamError.ENVELOPE_INVALID));
    }

    @Test
    void parseRejectsBrokenJson() {
        assertThatThrownBy(() -> EventEnvelopes.parse("not-json", Object.class, mapper))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(StreamError.ENVELOPE_INVALID));
    }
}
