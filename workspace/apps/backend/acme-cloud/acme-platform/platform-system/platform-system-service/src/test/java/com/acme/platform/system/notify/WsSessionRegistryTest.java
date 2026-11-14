package com.acme.platform.system.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS 会话注册表单测（REQ-20261114-120102）：注册 / 注销 / 定向推送 / 发送失败回收 /
 * 静默超心跳周期服务端断开并回收（AC-9）。
 */
class WsSessionRegistryTest {

    private final NotifyProperties properties = new NotifyProperties();
    private final WsSessionRegistry registry = new WsSessionRegistry(properties);

    @AfterEach
    void stopSweeper() {
        registry.stopSweeper();
    }

    private static WebSocketSession session(String id, String userId, boolean open) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WsSessionRegistry.ATTR_USER_ID, userId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        return session;
    }

    @Test
    void pushDeliversFrameToAllLocalSessionsOfUser() throws Exception {
        WebSocketSession first = session("s1", "42", true);
        WebSocketSession second = session("s2", "42", true);
        registry.register("42", first);
        registry.register("42", second);
        registry.register("43", session("s3", "43", true));

        registry.push("42", "{\"type\":\"message:new\"}");

        verify(first).sendMessage(any(TextMessage.class));
        verify(second).sendMessage(any(TextMessage.class));
    }

    @Test
    void pushSkipsOtherUsersAndClosedSessions() throws Exception {
        WebSocketSession closed = session("s1", "42", false);
        WebSocketSession other = session("s3", "43", true);
        registry.register("42", closed);
        registry.register("43", other);

        registry.push("42", "frame");

        verify(closed, never()).sendMessage(any());
        verify(other, never()).sendMessage(any());
    }

    @Test
    void sendFailureUnregistersAndClosesBrokenSession() throws Exception {
        WebSocketSession broken = session("s1", "42", true);
        doThrow(new IOException("peer gone")).when(broken).sendMessage(any());
        registry.register("42", broken);

        registry.push("42", "frame");

        verify(broken).close(any(CloseStatus.class));
        assertThat(registry.hasSessionsFor("42")).isFalse();
    }

    @Test
    void sweepIdleClosesSilentSessionsBeyondHeartbeatPeriod() throws Exception {
        // 超时周期收敛为 0：任何早于扫描时刻的活动均视为超期（保持用例瞬时性）
        properties.setIdleTimeout(java.time.Duration.ZERO);
        WebSocketSession idle = session("s1", "42", true);
        WebSocketSession alive = session("s2", "43", true);
        registry.register("42", idle);
        registry.register("43", alive);
        TimeUnit.MILLISECONDS.sleep(5);
        registry.touch(alive);

        registry.sweepIdle();

        verify(idle).close(any(CloseStatus.class));
        verify(alive, never()).close(any(CloseStatus.class));
        assertThat(registry.hasSessionsFor("42")).isFalse();
        assertThat(registry.hasSessionsFor("43")).isTrue();
    }

    @Test
    void unregisterIsIdempotent() throws Exception {
        WebSocketSession session = session("s1", "42", true);
        registry.register("42", session);
        registry.unregister(session);
        registry.unregister(session);
        assertThat(registry.hasSessionsFor("42")).isFalse();
        assertThat(registry.size()).isZero();
    }
}
