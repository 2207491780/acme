package com.acme.platform.system.notify;

import com.acme.platform.system.AbstractSystemIntegrationTest;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261119-082531 APP 推送触发链路（platform-system notify，AC-6 触发面开启态）服务域集成测试
 * （Testcontainers PG+Redis，装配口径照抄 {@code REQ20261119082531ProfilePreferenceIntegrationTest}；
 * 无 Docker 自动跳过）。独立 @SpringBootTest 上下文——ExpoPushSender 经
 * {@code @ConditionalOnProperty(acme.notify.push.enabled=true)} 按静态属性装配，无法在关闭态
 * 上下文内翻转（关闭态触发面见 ProfilePreference IT 的 pushDisabledCausesZeroHttpOutbound）。
 *
 * <p>覆盖：messagePushEnabledWithTokenTriggersExpoApiCall——站内信新消息（POST /admin/v1/system/messages）
 * → 事务提交 → AfterCommitEventListener 旁路（PushNotifyListener）→ ExpoPushSender 对收件人
 * 注册令牌逐个 POST Expo v2 端点（HTTP 桩断言载荷 to=token / title=消息标题，AC-6）；
 * messagePushWithoutTokenCausesZeroHttpOutbound——无令牌收件人零外呼（确认记录⑥）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 端点注入：acme.notify.push.url 指向本用例 HTTP 桩（类加载期启动于静态初始化器，
 *       端口先于 Spring 上下文装配可得——@DynamicPropertySource 引用静态端口字段）。</li>
 *   <li>AMB-2 异步派发：send 为 @Async fire-and-forget（PushNotifyConfig @EnableAsync）——
 *       断言前有限轮询桩命中（≤5s），不依赖响应时序。</li>
 *   <li>AMB-3 载荷：{to, title, body, priority=default}（ExpoPushSender.PushPayload，body=title 同文）；
 *       断言 to=注册令牌、title=消息标题。</li>
 * </ul>
 */
class REQ20261119082531PushNotifyTriggerIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 收件人（注册令牌且接收站内信）。 */
    private static final long RECIPIENT_ID = 903001L;

    /** Expo 发送端 HTTP 桩（类加载期启动，AMB-1：端口先于上下文装配可得）。 */
    private static final HttpServer STUB;

    /** 桩端点 URL（acme.notify.push.url 注入值）。 */
    private static final String PUSH_URL;

    private static final AtomicInteger HITS = new AtomicInteger();

    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Expo HTTP 桩启动失败", ex);
        }
        PUSH_URL = "http://127.0.0.1:" + STUB.getAddress().getPort() + "/--/api/v2/push/send";
        STUB.createContext("/--/api/v2/push/send", exchange -> {
            LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            HITS.incrementAndGet();
            byte[] resp = "{\"data\":[{\"status\":\"ok\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        STUB.start();
    }

    @DynamicPropertySource
    static void pushEndpointProperties(DynamicPropertyRegistry registry) {
        // AC-6 触发链路开启态：发送器 bean 装配 + 端点指向本用例桩（AMB-1）
        registry.add("acme.notify.push.enabled", () -> "true");
        registry.add("acme.notify.push.url", PUSH_URL::toString);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        seedUser(RECIPIENT_ID, "req082531-push-recipient");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_user where id = ?", RECIPIENT_ID);
        jdbcTemplate.update("delete from sys_push_token");
        jdbcTemplate.update("delete from sys_message");
        HITS.set(0);
        LAST_BODY.set(null);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-6：开启态 + 有令牌 → Expo API 被调用（载荷 to=token、title=消息标题） ----------

    @Test
    void messagePushEnabledWithTokenTriggersExpoApiCall() throws Exception {
        String token = "ExponentPushToken[req082531-trigger-1]";
        // 收件人注册推送令牌
        assertThat(mvc.perform(performAs(RECIPIENT_ID, post("/admin/v1/system/me/push-tokens"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\", \"platform\": \"ios\"}".formatted(token)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // 超管发站内信（完整触发链：事务提交 → PushNotifyListener 旁路 → ExpoPushSender @Async）
        assertThat(mvc.perform(performAs(1L, post("/admin/v1/system/messages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientIds": [%d], "title": "推送触发标题", "content": "推送触发正文"}
                                """.formatted(RECIPIENT_ID)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        awaitHits(1);
        assertThat(HITS.get()).as("推送开启且收件人有令牌：Expo API 应被调用恰好一次").isEqualTo(1);
        JsonNode payload = JSON.readTree(LAST_BODY.get());
        assertThat(payload.path("to").asText()).as("载荷 to=注册令牌（AMB-3）").isEqualTo(token);
        assertThat(payload.path("title").asText()).as("载荷 title=消息标题").isEqualTo("推送触发标题");
    }

    // ---------- AC-6：开启态 + 无令牌 → 零外呼（确认记录⑥） ----------

    @Test
    void messagePushWithoutTokenCausesZeroHttpOutbound() throws Exception {
        // 收件人不注册任何推送令牌
        assertThat(mvc.perform(performAs(1L, post("/admin/v1/system/messages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientIds": [%d], "title": "无令牌标题", "content": "无令牌正文"}
                                """.formatted(RECIPIENT_ID)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        Thread.sleep(500);
        assertThat(HITS.get()).as("无令牌收件人零行为（不外呼）").isZero();
    }

    // ---------- 支撑 ----------

    /** 有限轮询桩命中次数（@Async 派发异步到达，AMB-2）。 */
    private static void awaitHits(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && HITS.get() < expected) {
            Thread.sleep(50);
        }
    }

    /** 预置 sys_user 行（收件人存在性，Message AMB-8 口径）。 */
    private void seedUser(long id, String username) {
        jdbcTemplate.update("""
                insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'default', ?, ?, null, null, 'ENABLED', false,
                        now(), 'req082531-it', now(), 'req082531-it', 0, false)
                """, id, username, username);
    }

    /** 指定主体发起的请求构建（内部令牌承载身份）。 */
    private MockHttpServletRequestBuilder performAs(long userId, MockHttpServletRequestBuilder request) {
        return request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                        String.valueOf(userId), "default", Channel.B, Set.of("super_admin"))));
    }
}
