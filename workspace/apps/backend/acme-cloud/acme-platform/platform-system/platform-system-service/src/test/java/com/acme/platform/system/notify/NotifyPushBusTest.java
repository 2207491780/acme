package com.acme.platform.system.notify;

import com.acme.common.serialization.Json;
import com.acme.common.serialization.LongToStringModule;
import com.acme.framework.core.support.KeyComposer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推送总线单测（REQ-20261114-120102，模式照 L1InvalidationBus 先例）：
 * 事件帧契约形态（type=message:new）；Redis 广播失败降级本实例投递（fail-open）；
 * 订阅回调投递本地会话（多实例扇出本地投递面）。
 */
class NotifyPushBusTest {

    /** 与生产同源的 mapper（common-serialization JsonModules.standard：Long→String 等）。 */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new LongToStringModule())
            .build();

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
    private final WsSessionRegistry registry = new WsSessionRegistry(new NotifyProperties());
    private final NotifyPushBus bus = new NotifyPushBus(redisTemplate, connectionFactory,
            new KeyComposer("platform-system"), provider(new Json(MAPPER)), provider(null), registry);

    /** ObjectProvider 单测桩（仅 getIfAvailable 语义）。 */
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @AfterEach
    void cleanup() {
        registry.stopSweeper();
    }

    @Test
    void channelUsesServicePrefix() {
        assertThat(bus.channel()).isEqualTo("platform-system:notify:ws");
    }

    @Test
    void redisBroadcastFailureFallsBackToLocalDelivery() throws Exception {
        doThrow(new RuntimeException("redis down")).when(redisTemplate).convertAndSend(anyString(), anyString());
        WebSocketSession session = sessionMock("42");
        registry.register("42", session);
        try {
            bus.onEvent(new NotifyPushEvent(42L, 7L, "下单提醒"));

            ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
            verify(session, atLeastOnce()).sendMessage(frame.capture());
            // 帧契约形态 {type:"message:new", messageId, title}；Long 经标准 Long→String 规约
            var event = MAPPER.readTree(frame.getValue().getPayload());
            assertThat(event.path("type").asText()).isEqualTo("message:new");
            assertThat(event.path("messageId").asText()).isEqualTo("7");
            assertThat(event.path("title").asText()).isEqualTo("下单提醒");
        } finally {
            registry.unregister(session);
        }
    }

    @Test
    void redisSubscriptionCallbackDeliversFrameToLocalSessions() throws Exception {
        WebSocketSession session = sessionMock("42");
        registry.register("42", session);
        try {
            // 载荷形态=确认记录⑨：{userId, messageId, title}
            String payload = MAPPER.writeValueAsString(new NotifyPushMessage(42L, 9L, "工单动态"));
            Message message = mock(Message.class);
            when(message.getBody()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));

            bus.onMessage(message, new byte[0]);

            ArgumentCaptor<TextMessage> delivered = ArgumentCaptor.forClass(TextMessage.class);
            verify(session, atLeastOnce()).sendMessage(delivered.capture());
            var event = MAPPER.readTree(delivered.getValue().getPayload());
            assertThat(event.path("type").asText()).isEqualTo("message:new");
            assertThat(event.path("messageId").asText()).isEqualTo("9");
            assertThat(event.path("title").asText()).isEqualTo("工单动态");
        } finally {
            registry.unregister(session);
        }
    }

    @Test
    void malformedSubscriptionPayloadIsSkippedWithoutThrowing() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));
        // BR-019：监听侧异常不外抛（避免中断 Redis listener 线程）
        bus.onMessage(message, new byte[0]);
        assertThat(registry.size()).isZero();
    }

    /** 构造带身份属性的 mock WS 会话（isOpen 且可捕获 sendMessage）。 */
    private static WebSocketSession sessionMock(String userId) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WsSessionRegistry.ATTR_USER_ID, userId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("unit-" + userId);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> null).when(session).sendMessage(any());
        return session;
    }
}
