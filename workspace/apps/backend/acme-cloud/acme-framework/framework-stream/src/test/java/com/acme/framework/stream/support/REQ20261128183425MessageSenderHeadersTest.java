package com.acme.framework.stream.support;

import com.acme.common.contract.EventEnvelope;
import com.acme.common.contract.ServiceHeaders;
import com.acme.common.serialization.Json;
import com.acme.common.serialization.JsonModules;
import com.acme.framework.stream.internal.BinderRouting;
import com.acme.framework.stream.internal.MessageDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发送门面扩展头重载（REQ-20261128-183425 P2-12 AC-3）：分区键等传输面附加头经
 * extraHeaders 合并进出站消息——协议头/上下文头/CONTENT_TYPE 由门面单点维护不被覆盖
 * （BR-014 口径保持）；null/空 extraHeaders 等价双参重载（向后兼容面锁定）。
 */
class REQ20261128183425MessageSenderHeadersTest {

    private final Map<String, Message<?>> sent = new LinkedHashMap<>();

    private final MessageDispatcher dispatcher = (binding, message) -> {
        sent.put(binding, message);
        return true;
    };

    private MessageSender sender() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        return new MessageSender(dispatcher, new Json(builder.build()), new BinderRouting(new StandardEnvironment()));
    }

    @Test
    void mergesExtraHeadersWithoutOverwritingProtocolHeadersWhenExtraHeadersProvided() {
        EventEnvelope<String> envelope = new EventEnvelope<>("m-ext-1", "trace-ext-1", "ik-ext-1", "v1", "hello");

        sender().send("out-0", envelope, Map.of("partitionKey", "instance-9001",
                ServiceHeaders.MESSAGE_ID, "spoof", MessageHeaders.CONTENT_TYPE, "text/plain"));

        Message<?> message = sent.get("out-0");
        assertThat(message).isNotNull();
        // 扩展头进入消息（分区键消费面）
        assertThat(message.getHeaders().get("partitionKey")).isEqualTo("instance-9001");
        // 门面单点头不被扩展头覆盖（BR-014）
        assertThat(message.getHeaders().get(ServiceHeaders.MESSAGE_ID)).isEqualTo("m-ext-1");
        assertThat(message.getHeaders().get(MessageHeaders.CONTENT_TYPE)).isEqualTo("application/json");
        assertThat(String.valueOf(message.getPayload())).contains("\"messageId\":\"m-ext-1\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullOrEmptyExtraHeadersBehaveLikeTwoArgOverloadWhenSending() {
        EventEnvelope<String> envelope = new EventEnvelope<>("m-ext-2", "trace-ext-2", "ik-ext-2", "v1", null);

        sender().send("out-0", envelope, (Map<String, Object>) null);
        Message<?> nullCase = sent.get("out-0");
        assertThat(nullCase).isNotNull();
        assertThat(nullCase.getHeaders().get(ServiceHeaders.MESSAGE_ID)).isEqualTo("m-ext-2");
        assertThat(nullCase.getHeaders().containsKey("partitionKey")).isFalse();

        sender().send("out-0", envelope, Map.of());
        Message<?> emptyCase = sent.get("out-0");
        assertThat(emptyCase.getHeaders().get(ServiceHeaders.MESSAGE_ID)).isEqualTo("m-ext-2");
        assertThat(emptyCase.getHeaders().containsKey("partitionKey")).isFalse();
    }

    @Test
    void nullHeaderEntriesAreSkippedWhenExtraHeadersContainNulls() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("partitionKey", "instance-9002");
        extra.put("ghost", null);

        sender().send("out-0", new EventEnvelope<>("m-ext-3", "t", "i", "v", null), extra);

        Message<?> message = sent.get("out-0");
        assertThat(message.getHeaders().get("partitionKey")).isEqualTo("instance-9002");
        assertThat(message.getHeaders().containsKey("ghost")).isFalse();
    }
}
