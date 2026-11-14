package com.acme.platform.system.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WS 握手鉴权拦截器（确认记录④⑦，AC-6）：token 载体=握手 query 参数 {@code ?token=}
 * （浏览器 WebSocket API 无法自定义请求头，前端可行性唯一解），验签失败拒绝（401）。
 * 网关对 /ws/system/** 不做端别验签（鉴权为 /admin|/app 前缀语义），握手令牌校验为本端点
 * 唯一强制门（同时覆盖经网关与直连两类请求，防直连绕过）；/ws/system/** 已列入服务侧
 * acme.security.public-paths（内部令牌过滤器豁免，确认记录⑦）。
 */
@Component
public class NotifyHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(NotifyHandshakeInterceptor.class);

    private final NotifyTokenVerifier tokenVerifier;

    public NotifyHandshakeInterceptor(NotifyTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String userId = tokenVerifier.verify(extractToken(request)).orElse(null);
        if (userId == null) {
            log.warn("WS 握手拒绝（用户令牌验签失败）: uri={}", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(WsSessionRegistry.ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无握手后处理
    }

    /** 握手令牌提取（确认记录⑦钉死：?token= query 参数）。 */
    private String extractToken(ServerHttpRequest request) {
        MultiValueMap<String, String> params =
                UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String token = params.getFirst("token");
        return token == null || token.isBlank() ? null : token.trim();
    }
}
