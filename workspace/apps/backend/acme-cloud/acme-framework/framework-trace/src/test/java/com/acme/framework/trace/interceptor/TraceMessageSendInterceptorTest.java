package com.acme.framework.trace.interceptor;

import com.acme.common.contract.TraceConstants;
import com.acme.common.context.TraceContext;
import com.acme.framework.trace.support.Traces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stream 发送侧拦截测试（FR-004、AC-005 发送侧；messageId 幂等语义）。
 */
class TraceMessageSendInterceptorTest {

    private final TraceMessageSendInterceptor interceptor = new TraceMessageSendInterceptor();

    @AfterEach
    void tearDown() {
        Traces.clear();
    }

    /** preSend 注入当前上下文同值头，消息本体不变。 */
    @Test
    void preSendInjectsCurrentValues() {
        Traces.bind(new TraceContext("t-msg", "s-msg"));
        var message = interceptor.preSend(MessageBuilder.withPayload("payload").build(), null);
        assertThat(message.getPayload()).isEqualTo("payload");
        assertThat(message.getHeaders().get(TraceConstants.TRACE_ID_HEADER)).isEqualTo("t-msg");
        assertThat(message.getHeaders().get(TraceConstants.SPAN_ID_HEADER)).isEqualTo("s-msg");
    }

    /** 无上下文：生成值仅随消息携带、不绑定（DQ-003）。 */
    @Test
    void preSendGeneratesWithoutBinding() {
        var message = interceptor.preSend(MessageBuilder.withPayload("payload").build(), null);
        assertThat((String) message.getHeaders().get(TraceConstants.TRACE_ID_HEADER)).hasSize(32);
        assertThat((String) message.getHeaders().get(TraceConstants.SPAN_ID_HEADER)).hasSize(16);
        assertThat(Traces.current()).isNull();
    }

    /** 补充 trace 头后 messageId/timestamp 不变（FR-004 幂等语义）。 */
    @Test
    void preSendKeepsMessageIdAndTimestamp() {
        Message<String> original = MessageBuilder.withPayload("payload").setHeader("biz-key", "v").build();
        MessageHeaders before = original.getHeaders();

        Message<?> sent = interceptor.preSend(original, null);

        assertThat(sent.getHeaders().getId()).isEqualTo(before.getId());
        assertThat(sent.getHeaders().getTimestamp()).isEqualTo(before.getTimestamp());
        assertThat(sent.getHeaders().get("biz-key")).isEqualTo("v");
        assertThat(sent.getHeaders().get(TraceConstants.TRACE_ID_HEADER)).isNotNull();
        assertThat(sent.getHeaders().get(TraceConstants.SPAN_ID_HEADER)).isNotNull();
    }

    /** 已带齐 trace 头：原消息直通，不重建（FR-004）。 */
    @Test
    void preSendKeepsMessageWhenTraceHeadersPresent() {
        Message<String> original = MessageBuilder.withPayload("payload")
                .setHeader(TraceConstants.TRACE_ID_HEADER, "t-keep")
                .setHeader(TraceConstants.SPAN_ID_HEADER, "s-keep")
                .build();

        Message<?> sent = interceptor.preSend(original, null);

        assertThat(sent).isSameAs(original);
        assertThat(sent.getHeaders().getId()).isEqualTo(original.getHeaders().getId());
        assertThat(sent.getHeaders().get(TraceConstants.TRACE_ID_HEADER)).isEqualTo("t-keep");
        assertThat(sent.getHeaders().get(TraceConstants.SPAN_ID_HEADER)).isEqualTo("s-keep");
    }

    /** 仅缺一头时只补缺的，不覆盖既有头（FR-004）。 */
    @Test
    void preSendFillsOnlyMissingHeader() {
        Message<String> original = MessageBuilder.withPayload("payload")
                .setHeader(TraceConstants.TRACE_ID_HEADER, "t-keep")
                .build();

        Message<?> sent = interceptor.preSend(original, null);

        assertThat(sent.getHeaders().get(TraceConstants.TRACE_ID_HEADER)).isEqualTo("t-keep");
        assertThat((String) sent.getHeaders().get(TraceConstants.SPAN_ID_HEADER)).hasSize(16);
        assertThat(sent.getHeaders().getId()).isEqualTo(original.getHeaders().getId());
    }
}
