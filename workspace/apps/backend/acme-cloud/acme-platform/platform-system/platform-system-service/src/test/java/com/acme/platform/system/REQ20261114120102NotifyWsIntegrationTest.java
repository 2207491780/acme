package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261114-120102 实时推送（WS /ws/system/notify）服务域集成测试（Testcontainers PG + Redis，
 * RANDOM_PORT 真实容器 + JDK 内置 WebSocket 客户端；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-6 握手鉴权（有效 token 建立 / 无效与缺失 token 拒绝）、AC-7 在线用户实时收到
 * message:new（端到端 &lt;1s）、AC-8 多实例扇出经 Redis pub/sub（独立订阅者模拟第二实例）、
 * AC-9 空闲超期服务端断开并回收会话（可配置短周期）。</p>
 *
 * <p>装配设计（委派单授权自定）：真实 Tomcat 承载 WS 升级（AC-6/9 需真实握手与连接生命周期）；
 * 消息发送经 MockMvc 驱动（与真实容器共享同一应用上下文与 beans，本地投递同源）；客户端用
 * JDK {@link java.net.http.WebSocket}（不引 spring-websocket 客户端 API，规避版本签名漂移）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>业务失败断言 {@code Result.code != "0"}（HTTP 200 语义）；WS 帧契约形态
 *       {@code {type:"message:new", messageId, title}}（AC-7 精确断言三字段）。</li>
 *   <li>握手 token（TL 裁决钉死，原 AMB-9 收敛）：{@code ?token=} query 参数 + 用户令牌域
 *       （ACME_USER_TOKEN_HMAC_SECRET 域，非内部令牌域）；用例按网关验签语义（{@code DefaultUserTokenVerifier}：
 *       JWS HS256 / issuer=acme-auth / claims sub,tenantId,channel,roles,tokenType,jti）以用户域密钥直接签发
 *       测试令牌（jjwt 经 framework-security 传递可用）；管理 API 触发仍走内部令牌域（两类令牌不得互验，11.6.2）。</li>
 *   <li>过滤器链共存口径（TL 裁决钉死 /ws/system/** 进服务侧 public-paths，原 AMB-10 收敛）：测试侧将
 *       /ws/system/notify 加入 {@code acme.security.public-paths}，令 WS 握手不被内部令牌 servlet 过滤器拦截、
 *       由握手拦截器自验 token。</li>
 *   <li>用户域验签配置键（服务侧读取路径）未指明——AMB-14：用例按网关既有键
 *       {@code acme.gateway.user-token.hmac-secret} 注入测试密钥（issuer 取网关默认 acme-auth）；若服务侧读取
 *       其他键，AC-6 有效握手用例将失败提示对齐。</li>
 *   <li>心跳/空闲回收配置键（TL 裁决钉死，原 AMB-11 收敛为单键）：{@code acme.notify.ws.idle-timeout}
 *       （Duration，缺省 30s）——用例注入 3s 短周期。</li>
 *   <li>AC-9「静默」语义未指明：按「客户端不发任何数据帧」口径断言服务端断开；JDK 客户端按协议自动回 pong，
 *       若实现以应用层心跳帧判定静默，用例需按实际心跳协议补帧——AMB-12。回收会话以「断开发生后继续向该用户
 *       发送消息仍成功（code=0，注册表无脏会话）」作行为代理。</li>
 *   <li>AC-8 多实例扇出装配：以独立 Lettuce 连接 PSUBSCRIBE '*' 模拟第二实例（不依赖频道命名，channel 命名
 *       实现自定）；扇出载荷已由 TL 裁决钉死为 {@code {userId, messageId, title}}——断言三者齐备（原 AMB-13 收敛）；
 *       第二实例的本地投递由「订阅到事件即具备投递输入」语义覆盖，本用例不重复实现投递。</li>
 *   <li>AC-7 延迟：帧到达 await 窗口 3s（诊断余量），实测端到端耗时断言 &lt;1s（契约口径；CI 抖动致边缘失败时
 *       按契约复核而非放宽）。</li>
 *   <li>前置依赖（REQ 变更清单，由 backend 任务引入后测试域方可编译）：spring-boot-starter-websocket（服务端 WS）、
 *       spring-boot-starter-data-redis（Lettuce，pub/sub 订阅模拟）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-ws-it-secret-0123456789abcdef",
        "acme.security.public-paths=/ws/system/notify",
        "acme.notify.ws.idle-timeout=3s",
        "acme.gateway.user-token.hmac-secret=req-ws-it-user-secret-0123456789abcdef"
})
class REQ20261114120102NotifyWsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String WS_PATH = "/ws/system/notify";
    private static final long ONLINE_USER = 2001L;
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PUSH_WINDOW = Duration.ofSeconds(3);
    private static final Duration PUBSUB_WINDOW = Duration.ofSeconds(5);
    private static final Duration IDLE_DISCONNECT_WINDOW = Duration.ofSeconds(8);

    /** 用户令牌域测试密钥（与 acme.gateway.user-token.hmac-secret 注入值一致）与网关默认签发方。 */
    private static final String USER_TOKEN_SECRET = "req-ws-it-user-secret-0123456789abcdef";
    private static final String USER_TOKEN_ISSUER = "acme-auth";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：多实例扇出总线 + AC-8 独立订阅者。 */
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    /** 本用例开启的 WS 连接（teardown 统一中断，防跨用例残留会话）。 */
    private final List<WebSocket> openedSockets = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        seedUser(ONLINE_USER, "req120102-ws-user-a");
    }

    @AfterEach
    void cleanUp() {
        for (WebSocket socket : openedSockets) {
            try {
                socket.abort();
            } catch (RuntimeException ignore) {
                // teardown 尽力而为
            }
        }
        openedSockets.clear();
        jdbcTemplate.update("delete from sys_message");
        jdbcTemplate.update("delete from sys_notice");
        jdbcTemplate.update("delete from sys_user where id = ?", ONLINE_USER);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-6：有效 token 握手 → 连接建立 ----------

    @Test
    void should_establishSession_when_handshakeWithValidToken() throws Exception {
        RecordingListener listener = new RecordingListener();

        WebSocket socket = connect(listener, userToken(String.valueOf(ONLINE_USER)));

        assertThat(socket).isNotNull();
        assertThat(listener.opened.await(2, TimeUnit.SECONDS))
                .as("有效 token 握手后连接应建立（onOpen 回调）").isTrue();
    }

    // ---------- AC-6：无效 token / 缺失 token → 握手拒绝 ----------

    @Test
    void should_rejectHandshake_when_tokenInvalid() throws Exception {
        RecordingListener listener = new RecordingListener();

        try {
            connect(listener, "req-120102-invalid-token");
            throw new AssertionError("无效 token 握手不应成功");
        } catch (ExecutionException expected) {
            assertHandshakeRejected(expected);
        }
    }

    @Test
    void should_rejectHandshake_when_tokenMissing() {
        RecordingListener listener = new RecordingListener();

        assertThatThrownBy(() -> connect(listener, null))
                .as("缺失 token 握手应被拒绝").isInstanceOf(ExecutionException.class);
    }

    // ---------- AC-7：用户在线，向其发消息 → WS 收到 message:new（端到端 <1s） ----------

    @Test
    void should_pushMessageNewFrameWithinOneSecond_when_messageSentToOnlineUser() throws Exception {
        RecordingListener listener = new RecordingListener();
        connect(listener, userToken(String.valueOf(ONLINE_USER)));
        assertThat(listener.opened.await(2, TimeUnit.SECONDS)).isTrue();

        String title = "req-120102-ws-push-title";
        long start = System.nanoTime();
        JsonNode sendBody = sendMessage(String.valueOf(ONLINE_USER), title);
        assertThat(sendBody.path("code").asText()).as("发送站内信应成功").isEqualTo("0");

        String frame = awaitFrame(listener, PUSH_WINDOW, candidate -> candidate.contains("message:new"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(frame).as("在线用户应实时收到 message:new 帧").isNotNull();
        JsonNode event = JSON.readTree(frame);
        assertThat(event.path("type").asText()).isEqualTo("message:new");
        assertThat(event.path("messageId").asText()).as("事件帧应携带 messageId").isNotBlank();
        assertThat(event.path("title").asText()).isEqualTo(title);
        assertThat(elapsedMillis)
                .as("端到端推送延迟应 <1s（AC-7 契约口径；实测 %d ms）", elapsedMillis)
                .isLessThan(1000L);
    }

    // ---------- AC-8：多实例扇出 —— Redis pub/sub 事件可达持有该用户会话的第二实例 ----------

    @Test
    void should_broadcastFanOutEventViaRedisPubSub_when_messageSentToUserHoldingSession() throws Exception {
        RecordingListener listener = new RecordingListener();
        connect(listener, userToken(String.valueOf(ONLINE_USER)));
        assertThat(listener.opened.await(2, TimeUnit.SECONDS)).isTrue();

        // 独立订阅者模拟第二实例/pod（PSUBSCRIBE '*' 不依赖频道命名，channel 实现自定）
        RedisClient redisClient = RedisClient.create(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        StatefulRedisPubSubConnection<String, String> pubSub = redisClient.connectPubSub();
        ConcurrentLinkedQueue<String[]> events = new ConcurrentLinkedQueue<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void psubscribed(String pattern, long count) {
                subscribed.countDown();
            }

            @Override
            public void message(String channel, String message) {
                events.add(new String[] {channel, message});
            }

            @Override
            public void message(String pattern, String channel, String message) {
                events.add(new String[] {channel, message});
            }
        });
        pubSub.async().psubscribe("*");
        assertThat(subscribed.await(2, TimeUnit.SECONDS))
                .as("模拟第二实例的订阅应先于发送就绪").isTrue();
        try {
            String title = "req-120102-fanout-title";
            JsonNode sendBody = sendMessage(String.valueOf(ONLINE_USER), title);
            assertThat(sendBody.path("code").asText()).as("发送站内信应成功").isEqualTo("0");

            String frame = awaitFrame(listener, PUBSUB_WINDOW, candidate -> candidate.contains("message:new"));
            assertThat(frame).as("本实例持有该用户会话，应本地投递 message:new").isNotNull();
            String messageId = JSON.readTree(frame).path("messageId").asText();
            assertThat(messageId).isNotBlank();

            assertThat(awaitTrue(PUBSUB_WINDOW, () -> anyEventFor(events, messageId, title)))
                    .as("扇出事件应经 Redis pub/sub 广播（载荷含 userId/messageId/title，TL 裁决钉死；"
                            + "第二实例订阅即可获得投递输入）").isTrue();
        } finally {
            pubSub.close();
            redisClient.shutdown();
        }
    }

    // ---------- AC-9：连接静默超心跳周期 → 服务端断开并回收会话 ----------

    @Test
    void should_disconnectAndReapIdleSession_when_silentBeyondConfiguredIdleWindow() throws Exception {
        RecordingListener listener = new RecordingListener();
        connect(listener, userToken(String.valueOf(ONLINE_USER)));
        assertThat(listener.opened.await(2, TimeUnit.SECONDS)).isTrue();

        // 客户端静默（不发送任何数据帧）；idle-timeout=3s（TL 裁决键名，原 AMB-11），断开窗口 8s 含余量
        assertThat(awaitTrue(IDLE_DISCONNECT_WINDOW, () -> listener.closed.isDone()))
                .as("空闲超期会话应被服务端断开（onClose/onError）").isTrue();

        // 回收行为代理：断开后继续向该用户发消息仍成功（注册表无脏会话，AMB-12）
        JsonNode sendBody = sendMessage(String.valueOf(ONLINE_USER), "req-120102-after-idle-title");
        assertThat(sendBody.path("code").asText())
                .as("会话回收后发送链路不应受阻").isEqualTo("0");
    }

    // ---------- 支撑 ----------

    /** WS 连接（token 为 null 时不带 query 参数）。 */
    private WebSocket connect(RecordingListener listener, String token) throws Exception {
        String url = "ws://localhost:" + port + WS_PATH
                + (token == null ? "" : "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        WebSocket socket = HTTP.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .get(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        openedSockets.add(socket);
        return socket;
    }

    private static void assertHandshakeRejected(ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof WebSocketHandshakeException handshake) {
            // 握手拒绝以非 101 升级响应表达（具体状态码由实现定）
            assertThat(handshake.getResponse().statusCode()).isNotEqualTo(101);
        }
        // 非 handshake 异常形态（如连接被直接关闭）同样满足「拒绝」语义
    }

    /** 扇出载荷判定（TL 裁决钉死 {userId, messageId, title}）：事件载荷须同时含三者。 */
    private boolean anyEventFor(ConcurrentLinkedQueue<String[]> events, String messageId, String title) {
        for (String[] event : events) {
            if (event[1].contains(String.valueOf(ONLINE_USER))
                    && event[1].contains(messageId)
                    && event[1].contains(title)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode sendMessage(String recipientId, String title) throws Exception {
        return call(post("/admin/v1/system/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "recipientIds", List.of(recipientId), "title", title,
                        "content", "<p>" + title + "</p>"))));
    }

    private String awaitFrame(RecordingListener listener, Duration timeout, Predicate<String> predicate) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (String frame : listener.frames) {
                if (predicate.test(frame)) {
                    return frame;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        for (String frame : listener.frames) {
            if (predicate.test(frame)) {
                return frame;
            }
        }
        return null;
    }

    private static boolean awaitTrue(Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException pollFailure) {
                // 条件查询前置状态未就绪，继续轮询
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 内部令牌域（acme.security.token.hmac-secret）：管理 API 触发面，与用户令牌域隔离（11.6.2）。 */
    private String internalToken(String userId) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, "default", Channel.B, Set.of("super_admin")));
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", internalToken(String.valueOf(ONLINE_USER)))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 用户令牌域测试令牌（TL 裁决钉死：WS 握手走用户令牌域）：按网关验签语义
     * （{@code DefaultUserTokenVerifier}：JWS HS256 / issuer=acme-auth / claims
     * sub,tenantId,channel,roles,tokenType,jti）以用户域密钥签发；与内部令牌域（11.6.2）严格隔离。
     */
    private String userToken(String userId) {
        javax.crypto.SecretKey key = Keys.hmacShaKeyFor(
                USER_TOKEN_SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .id("req-120102-ws-jti-" + userId)
                .issuer(USER_TOKEN_ISSUER)
                .subject(userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .claim("tenantId", "default")
                .claim("channel", "B")
                .claim("roles", Set.of("super_admin"))
                .claim("tokenType", "access")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private void seedUser(long id, String username) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, null, null, 'ENABLED', false,
                                now(), 'req120102-it', now(), 'req120102-it', 0, false)
                        """,
                id, username, username);
    }

    /**
     * JDK WebSocket 录制监听器：onOpen 门闩、帧收集（分片聚合）、关闭/异常完成信号。
     * 心跳类帧（若实现以文本帧发心跳）由各用例谓词过滤，不参与 message:new 断言。
     */
    private static final class RecordingListener implements WebSocket.Listener {

        private final CountDownLatch opened = new CountDownLatch(1);
        private final CompletableFuture<String> closed = new CompletableFuture<>();
        private final ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();
        private final StringBuilder pending = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            opened.countDown();
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                frames.add(pending.toString());
                pending.setLength(0);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode + " " + reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
            WebSocket.Listener.super.onError(webSocket, error);
        }
    }
}
