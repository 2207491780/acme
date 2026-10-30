package com.acme.framework.trace.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.framework.trace.support.TraceMessages;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stream 发送侧薄适配（FR-004）：preSend 仅在缺 trace 头时补充（{@link TraceMessages#applyIfAbsent}），
 * 并保留原 {@link MessageHeaders#ID}/{@link MessageHeaders#TIMESTAMP}（messageId 幂等语义，
 * 全量重建会重新生成 id）；消费侧提取接线归 framework-stream P4（Q-003）。
 */
public final class TraceMessageSendInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        Map<String, Object> headers = message.getHeaders();
        if (hasText(headers.get(TraceConstants.TRACE_ID_HEADER))
                && hasText(headers.get(TraceConstants.SPAN_ID_HEADER))) {
            // 已带齐 trace 头：原消息直通，id/timestamp 不变（FR-004 幂等语义）
            return message;
        }
        Map<String, Object> merged = new HashMap<>(headers);
        TraceMessages.applyIfAbsent(merged);
        MessageHeaders original = message.getHeaders();
        return MessageBuilder.createMessage(message.getPayload(),
                new StableMessageHeaders(merged, original.getId(), original.getTimestamp()));
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    /** 沿用原 id/timestamp 的消息头重建（FR-004 messageId 幂等语义）。 */
    private static final class StableMessageHeaders extends MessageHeaders {

        private StableMessageHeaders(Map<String, Object> raw, UUID id, Long timestamp) {
            super(raw, id, timestamp);
        }
    }
}
