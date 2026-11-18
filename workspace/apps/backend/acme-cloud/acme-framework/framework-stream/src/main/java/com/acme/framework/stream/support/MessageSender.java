package com.acme.framework.stream.support;

import com.acme.common.contract.EventEnvelope;
import com.acme.common.contract.ServiceHeaders;
import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import com.acme.common.core.BizException;
import com.acme.common.serialization.Json;
import com.acme.framework.core.support.ContextHeaders;
import com.acme.framework.stream.internal.BinderRouting;
import com.acme.framework.stream.internal.MessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * 发送门面（FR-013/AC-013）：产出 EventEnvelope JSON + 协议头（DP-009：Message-Id/Idempotent-Key 预解析头）+
 * ServiceHeaders 上下文头（BR-014，缺值不注入）；发送失败显式可感知（FR-017）。
 */
public class MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MessageSender.class);

    private final MessageDispatcher dispatcher;
    private final Json json;
    private final BinderRouting binderRouting;

    public MessageSender(MessageDispatcher dispatcher, Json json, BinderRouting binderRouting) {
        this.dispatcher = dispatcher;
        this.json = json;
        this.binderRouting = binderRouting;
    }

    /** 发送（FR-013）：输入=信封 + 目标绑定。 */
    public <P> void send(String binding, EventEnvelope<P> envelope) {
        if (envelope == null) {
            throw new BizException(StreamError.ENVELOPE_INVALID, "事件信封不能为空");
        }
        binderRouting.validate(binding);
        Message<?> message = MessageBuilder.withPayload(json.toJson(envelope))
                .copyHeaders(outboundHeaders(envelope))
                .setHeader(MessageHeaders.CONTENT_TYPE, "application/json")
                .build();
        boolean accepted;
        try {
            accepted = dispatcher.send(binding, message);
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException(StreamError.SEND_FAILED, "消息发送失败", ex);
        }
        if (!accepted) {
            throw new BizException(StreamError.SEND_FAILED, "消息未被接收: " + binding);
        }
        log.debug("消息已发送（FR-013）: binding={} messageId={}", binding, envelope.getMessageId());
    }

    /** 发送（缺省信封：messageId/幂等键/traceId 口径见 8.3）。 */
    public <P> void send(String binding, P payload, String version) {
        send(binding, EventEnvelopes.newEnvelope(payload, version));
    }

    /** 出站头（协议头 + 上下文头；traceId 以信封为单一来源，8.3 归属划分）。 */
    private Map<String, Object> outboundHeaders(EventEnvelope<?> envelope) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(ServiceHeaders.MESSAGE_ID, envelope.getMessageId());
        headers.put(ServiceHeaders.IDEMPOTENT_KEY, envelope.getIdempotentKey());
        headers.put(TraceConstants.TRACE_ID_HEADER, envelope.getTraceId());
        headers.put(TraceConstants.SPAN_ID_HEADER, spanId());
        ContextHeaders.outbound().forEach(headers::put);
        return headers;
    }

    private static String spanId() {
        TraceContext current = TraceContext.get();
        return current == null || current.spanId() == null
                ? java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : current.spanId();
    }
}
