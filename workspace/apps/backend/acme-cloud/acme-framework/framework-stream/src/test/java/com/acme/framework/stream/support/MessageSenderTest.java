package com.acme.framework.stream.support;

import com.acme.common.contract.EventEnvelope;
import com.acme.common.contract.ServiceHeaders;
import com.acme.common.contract.TraceConstants;
import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.LocaleContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.serialization.Json;
import com.acme.common.serialization.JsonModules;
import com.acme.framework.stream.internal.BinderRouting;
import com.acme.framework.stream.internal.MessageDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.messaging.Message;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发送门面（FR-013/AC-013：协议头 + 上下文头；BR-014 缺值不注入；FR-017 发送失败显式）。
 */
class MessageSenderTest {

    private final Map<String, Message<?>> sent = new LinkedHashMap<>();

    private final MessageDispatcher dispatcher = (binding, message) -> {
        sent.put(binding, message);
        return true;
    };

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
        ClientContext.clear();
        LocaleContext.clear();
    }

    private MessageSender sender() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        return new MessageSender(dispatcher, new Json(builder.build()), new BinderRouting(new StandardEnvironment()));
    }

    @Test
    void sendsEnvelopeWithProtocolAndContextHeaders() {
        UserContext.bind(new UserContext("u-1", Set.of()));
        TenantContext.bind(new TenantContext("t-1"));
        ClientContext.bind(new ClientContext(Channel.B, "d-1", "1.0"));
        LocaleContext.bind(new LocaleContext(Locale.forLanguageTag("zh-CN")));
        EventEnvelope<String> envelope = new EventEnvelope<>("m-1", "trace-1", "ik-1", "v1", "hello");

        sender().send("out-0", envelope);

        Message<?> message = sent.get("out-0");
        assertThat(message).isNotNull();
        assertThat(message.getHeaders().get(ServiceHeaders.MESSAGE_ID)).isEqualTo("m-1");
        assertThat(message.getHeaders().get(ServiceHeaders.IDEMPOTENT_KEY)).isEqualTo("ik-1");
        assertThat(message.getHeaders().get(TraceConstants.TRACE_ID_HEADER)).isEqualTo("trace-1");
        assertThat(message.getHeaders().get(ServiceHeaders.USER_ID)).isEqualTo("u-1");
        assertThat(message.getHeaders().get(ServiceHeaders.OPERATOR)).isEqualTo("u-1");
        assertThat(message.getHeaders().get(ServiceHeaders.TENANT_ID)).isEqualTo("t-1");
        assertThat(message.getHeaders().get(ServiceHeaders.CHANNEL)).isEqualTo("B");
        assertThat(message.getHeaders().get(ServiceHeaders.LOCALE)).isEqualTo("zh-CN");
        assertThat(String.valueOf(message.getPayload())).contains("\"messageId\":\"m-1\"");
    }

    @Test
    void missingContextValuesNotInjected() {
        sender().send("out-0", new EventEnvelope<>("m-2", "trace-2", "ik-2", "v1", null));
        Message<?> message = sent.get("out-0");
        assertThat(message.getHeaders().containsKey(ServiceHeaders.USER_ID)).isFalse();
        assertThat(message.getHeaders().containsKey(ServiceHeaders.TENANT_ID)).isFalse();
        assertThat(message.getHeaders().containsKey(ServiceHeaders.LOCALE)).isFalse();
    }

    @Test
    void unknownBinderFailsExplicitly() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("spring.cloud.stream.bindings.out-0.binder", "ghost",
                        "spring.cloud.stream.bindings.out-0.destination", "topic")));
        MessageSender sender = new MessageSender(dispatcher,
                new Json(JsonMapper.builder().build()), new BinderRouting(environment));
        assertThatThrownBy(() -> sender.send("out-0", new EventEnvelope<>("m-3", "t", "i", "v", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(StreamError.SEND_FAILED));
    }

    @Test
    void sendFailureMappedExplicitly() {
        MessageDispatcher failing = (binding, message) -> {
            throw new IllegalStateException("binder down");
        };
        MessageSender sender = new MessageSender(failing,
                new Json(JsonMapper.builder().build()), new BinderRouting(new StandardEnvironment()));
        assertThatThrownBy(() -> sender.send("out-0", new EventEnvelope<>("m-4", "t", "i", "v", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(StreamError.SEND_FAILED));
    }

    @Test
    void envelopeValidationIsExplicit() {
        assertThatThrownBy(() -> sender().send("out-0", null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(StreamError.ENVELOPE_INVALID));
    }
}
