package com.acme.framework.stream.support;

import com.acme.common.contract.EventEnvelope;
import com.acme.common.core.BizException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

/**
 * 信封构造/解析辅助（DP-009 定稿、DP-015 使用样例随实现补全）：
 * 发送侧缺省信封（messageId/幂等键/traceId 口径见 8.3）；消费侧按 payload 类型直接类型化反序列化
 * （单次 readValue 为信封结构，不经 Map 往返）；解析失败统一 {@code ENVELOPE_INVALID}。
 */
public final class EventEnvelopes {

    private EventEnvelopes() {
    }

    /**
     * 缺省信封（FR-013）：messageId=UUID；traceId=当前或新生成（Traces 口径）；
     * 幂等键缺省回落 messageId（8.3）。
     */
    public static <P> EventEnvelope<P> newEnvelope(P payload, String version) {
        String messageId = java.util.UUID.randomUUID().toString();
        String traceId = currentTraceId();
        return new EventEnvelope<>(messageId, traceId, messageId, version, payload);
    }

    /**
     * 解析信封 JSON 为类型化信封（消费侧；payload 允许 null）。
     * 直接类型化反序列化信封结构；字段非法/反序列化失败统一 {@link StreamError#ENVELOPE_INVALID}。
     */
    public static <P> EventEnvelope<P> parse(String json, Class<P> payloadType, JsonMapper mapper) {
        if (json == null || json.isBlank()) {
            throw new BizException(StreamError.ENVELOPE_INVALID, "消息信封为空（DP-009）");
        }
        EnvelopeRecord<P> record;
        try {
            TypeFactory types = mapper.getTypeFactory();
            record = mapper.readValue(json,
                    payloadType == null
                            ? types.constructParametricType(EnvelopeRecord.class, Object.class)
                            : types.constructParametricType(EnvelopeRecord.class, payloadType));
        } catch (RuntimeException ex) {
            throw new BizException(StreamError.ENVELOPE_INVALID, "消息信封解析失败（DP-009）", ex);
        }
        try {
            return new EventEnvelope<>(record.messageId(), record.traceId(), record.idempotentKey(),
                    record.version(), record.payload());
        } catch (IllegalArgumentException ex) {
            throw new BizException(StreamError.ENVELOPE_INVALID, "消息协议字段非法（DP-009）", ex);
        }
    }

    /** 信封反序列化记录（与 EventEnvelope 协议字段集一致，DP-009）。 */
    record EnvelopeRecord<P>(String messageId, String traceId, String idempotentKey, String version, P payload) {
    }

    private static String currentTraceId() {
        com.acme.common.context.TraceContext trace = com.acme.common.context.TraceContext.get();
        return trace == null || trace.traceId() == null
                ? java.util.UUID.randomUUID().toString().replace("-", "")
                : trace.traceId();
    }
}
