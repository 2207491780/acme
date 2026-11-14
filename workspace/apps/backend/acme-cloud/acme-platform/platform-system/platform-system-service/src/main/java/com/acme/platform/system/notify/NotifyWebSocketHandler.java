package com.acme.platform.system.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * 站内信实时推送 handler（REQ-20261114-120102）：连接建立即注册（验签 userId 由握手拦截器写入
 * session attributes），断开即注销；上行任意文本刷新心跳活动时间，"ping" 回 "pong"
 * （应用层心跳，配合注册表 30s 静默回收，AC-9）。下行事件帧唯一形态见 {@link NotifyFrame}。
 */
@Component
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NotifyWebSocketHandler.class);

    private final WsSessionRegistry registry;

    public NotifyWebSocketHandler(WsSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Object userId = session.getAttributes().get(WsSessionRegistry.ATTR_USER_ID);
        if (userId == null) {
            // 防御分支：握手拦截器已保证验签注册，此处兜底拒绝无身份会话
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        registry.register(String.valueOf(userId), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        registry.touch(session);
        if ("ping".equalsIgnoreCase(message.getPayload())) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage("pong"));
                    }
                }
            } catch (IOException | RuntimeException ex) {
                log.debug("WS 心跳应答失败: sessionId={}", session.getId());
            }
        }
    }
}
