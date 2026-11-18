package com.acme.platform.system.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配（REQ-20261114-120102）：站内信实时推送端点 {@code /ws/system/notify}。
 * 握手鉴权经 {@link NotifyHandshakeInterceptor}（用户令牌验签失败拒绝=AC-6；密钥键=网关同键单源
 * {@code acme.gateway.user-token.*}，确认记录⑬）；跨域归网关统一处理（BR-011），WS 握手源放开由
 * 握手令牌校验兜底。服务侧 acme.security.public-paths 对 {@code /ws/system/**} 放行内部令牌过滤
 * （握手令牌校验为唯一强制门，确认记录⑦）。
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties({NotifyProperties.class, NotifyUserTokenProperties.class})
public class WebSocketNotifyConfig implements WebSocketConfigurer {

    /** 站内信推送端点（契约 §API，路径逐字一致）。 */
    public static final String NOTIFY_ENDPOINT = "/ws/system/notify";

    private final NotifyWebSocketHandler notifyHandler;
    private final NotifyHandshakeInterceptor handshakeInterceptor;

    public WebSocketNotifyConfig(NotifyWebSocketHandler notifyHandler,
                                 NotifyHandshakeInterceptor handshakeInterceptor) {
        this.notifyHandler = notifyHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifyHandler, NOTIFY_ENDPOINT)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
