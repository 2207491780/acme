package com.acme.platform.system.notify;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS 握手鉴权拦截器单测（REQ-20261114-120102，AC-6）：有效 token 建立连接并写入身份属性；
 * 无效 / 缺失 token 拒绝（401）——经网关与直连同门（防直连绕过）。
 */
class NotifyHandshakeInterceptorTest {

    private static final String SECRET = "notify-ws-handshake-test-secret-0123456789abcdef";

    private NotifyHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        NotifyUserTokenProperties properties = new NotifyUserTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setIssuer("acme-auth");
        interceptor = new NotifyHandshakeInterceptor(new NotifyTokenVerifier(properties));
    }

    private static String accessToken() {
        return Jwts.builder()
                .issuer("acme-auth")
                .subject("42")
                .claim("tokenType", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void validQueryTokenEstablishesConnectionWithIdentityAttribute() {
        // 确认记录⑦：token 载体=?token= query 参数（钉死）
        ServerHttpRequest request = request(URI.create("ws://host/ws/system/notify?token=" + accessToken()),
                null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(WsSessionRegistry.ATTR_USER_ID)).isEqualTo("42");
    }

    @Test
    void missingTokenRejectsHandshakeWithUnauthorized() {
        ServerHttpRequest request = request(URI.create("ws://host/ws/system/notify"), null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidTokenRejectsHandshakeWithUnauthorized() {
        ServerHttpRequest request = request(
                URI.create("ws://host/ws/system/notify?token=forged.jwt.value"), null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    private static ServerHttpRequest request(URI uri, String authorizationHeader) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(uri);
        HttpHeaders headers = new HttpHeaders();
        if (authorizationHeader != null) {
            headers.set("Authorization", authorizationHeader);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }
}
