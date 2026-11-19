package com.acme.platform.system.notify;

import com.acme.platform.system.domain.SysPushToken;
import com.acme.platform.system.infrastructure.repository.SysPushTokenRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Expo 推送发送器单测（REQ-20261119-082531，AC-6 发送器单元面；纯 JUnit 无 Spring 上下文，
 * 模式照同包 NotifyPushBusTest 先例）。覆盖：
 * <ul>
 *   <li>开关语义：acme.notify.push.enabled 默认 false；关闭时零外呼（契约确认⑥）；</li>
 *   <li>HTTP 桩断言载荷 {to=push token, title}（Expo 规约），POST + application/json；</li>
 *   <li>失败 fire-and-forget：非 2xx / 连接拒绝 → WARN 日志不外抛（WARN 口径由实现承担，
 *       无日志捕获依赖，单测面只断言不抛）。</li>
 * </ul>
 * HTTP 桩用 JDK 内置 com.sun.net.httpserver（无新依赖）；端点 URL 经 PushProperties.url
 * 注入桩地址——生产端点默认 https://exp.host/--/api/v2/push/send（契约确认⑥），URL 必须可注入
 * 否则 AC-6「mock HTTP 断言」无法落地。
 * <p>签名对齐（backend 实现定稿，tester AMB 回填）：构造
 * {@code ExpoPushSender(RestClient, PushProperties, SysPushTokenRepository)}——sender 承担
 * recipientId→token 扇出（findByUserId 数据源，Mockito 桩），单测直调 {@code send(userId, title)}
 * 同步路径（new 直构无 @Async 代理）；配置类为独立 {@link PushProperties}（前缀 acme.notify.push，
 * enabled 默认 false / url 可注入），不再挂 NotifyProperties（WS 域配置与推送域配置分离）。
 */
class REQ20261119082531ExpoPushSenderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TOKEN = "ExponentPushToken[stub-token-1]";
    private static final String TITLE = "下单提醒";
    private static final long USER_ID = 930001L;

    private HttpServer stub;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicBoolean failWith500 = new AtomicBoolean(false);

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/--/api/v2/push/send", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastMethod.set(exchange.getRequestMethod());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            hits.incrementAndGet();
            byte[] resp = "{\"data\":[{\"status\":\"ok\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(failWith500.get() ? 500 : 200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        stub.start();
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    /** 构造发送器并把端点指向本用例的 HTTP 桩（构造面覆盖：RestClient + 配置 + 令牌仓库桩）。 */
    private ExpoPushSender sender(boolean enabled) {
        PushProperties properties = new PushProperties();
        properties.setEnabled(enabled);
        String url = "http://127.0.0.1:" + stub.getAddress().getPort() + "/--/api/v2/push/send";
        properties.setUrl(url);
        SysPushTokenRepository repository = mock(SysPushTokenRepository.class);
        when(repository.findByUserId(anyLong())).thenReturn(List.of(
                new SysPushToken(USER_ID, TOKEN, "ios")));
        return new ExpoPushSender(RestClient.builder().baseUrl(url).build(), properties, repository);
    }

    /** 有限等待桩收到第 expected 次请求（兼容同步直调与内部异步派发两种实现形态）。 */
    private void awaitHits(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && hits.get() < expected) {
            Thread.sleep(20);
        }
    }

    @Test
    void pushEnabledDefaultsToFalse() {
        // 契约确认⑥：开关默认 false（无 projectId/网络时零行为）
        assertThat(new PushProperties().isEnabled()).isFalse();
    }

    @Test
    void sendsExpoPayloadWithTokenAndTitleWhenEnabled() throws Exception {
        ExpoPushSender sender = sender(true);

        sender.send(USER_ID, TITLE);
        awaitHits(1);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastContentType.get()).contains("application/json");
        var payload = MAPPER.readTree(lastBody.get());
        assertThat(payload.path("to").asText()).isEqualTo(TOKEN);
        assertThat(payload.path("title").asText()).isEqualTo(TITLE);
    }

    @Test
    void skipsHttpOutboundWhenDisabled() throws Exception {
        ExpoPushSender sender = sender(false);

        sender.send(USER_ID, TITLE);
        // 短暂宽限以防实现内部异步派发；关闭态必须零外呼
        Thread.sleep(200);

        assertThat(hits.get()).isZero();
        assertThat(lastBody.get()).isNull();
    }

    @Test
    void httpFailureResponseDoesNotThrow() {
        failWith500.set(true);
        ExpoPushSender sender = sender(true);

        // fire-and-forget：非 2xx → WARN 不外抛
        assertThatCode(() -> sender.send(USER_ID, TITLE)).doesNotThrowAnyException();
    }

    @Test
    void unreachableEndpointDoesNotThrow() throws Exception {
        // 拒绝连接的空闲端口：绑定后立即关闭
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }
        PushProperties properties = new PushProperties();
        properties.setEnabled(true);
        String url = "http://127.0.0.1:" + deadPort + "/--/api/v2/push/send";
        properties.setUrl(url);
        SysPushTokenRepository repository = mock(SysPushTokenRepository.class);
        when(repository.findByUserId(anyLong())).thenReturn(List.of(
                new SysPushToken(USER_ID, TOKEN, "ios")));
        ExpoPushSender sender = new ExpoPushSender(
                RestClient.builder().baseUrl(url).build(), properties, repository);

        assertThatCode(() -> sender.send(USER_ID, TITLE)).doesNotThrowAnyException();
    }
}
