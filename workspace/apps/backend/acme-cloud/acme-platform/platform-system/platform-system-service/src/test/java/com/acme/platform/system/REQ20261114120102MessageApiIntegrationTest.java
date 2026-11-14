package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261114-120102 站内信（platform-system）服务域集成测试（Testcontainers PG + Redis，照抄
 * {@code SystemIntegrationTest}/{@code REQ20261114071607FileServiceIntegrationTest} 装配口径；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-3 发送后仅收件人可见（非收件人/发送者不可见）、AC-4 单条已读/全部已读
 * （read/read_at 落库、unread-count 递减/清零、isRead 筛选）、AC-5 他人消息 read → MESSAGE_FORBIDDEN
 * 与不存在消息 → MESSAGE_NOT_FOUND、消息域无权限统一拒绝（补充，权限面口径同 AC-1）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>业务失败断言统一响应体 {@code Result.code != "0"}（不断言 4xx，HTTP 200 语义）；
 *       {@code MESSAGE_FORBIDDEN}/{@code MESSAGE_NOT_FOUND} 为契约明示错误码，精确断言。</li>
 *   <li>发送请求体字段 recipientIds/title/content、收件箱 isRead 筛选参数为契约明示命名。</li>
 *   <li>unread-count 响应 data 形态未指明（裸数值 vs 对象包 count）：用例双兼容取值——AMB-7。</li>
 *   <li>recipientIds 存在性校验未指明：收件人预置真实 sys_user 规避两种实现口径分歧——AMB-8。</li>
 *   <li>sys_message 列名按契约变更清单（sender/recipient/title/content/is_read/read_at），
 *       落库断言经 JdbcTemplate 原生 SQL（绕开租户/软删过滤面）。</li>
 *   <li>各操作者以 super_admin 角色令牌驱动、仅 userId 区分身份（权限判定走 V8 种子对 super_admin
 *       全量绑定的既有惯例）；无权限用例以未绑定角色令牌驱动，仅断言 Result.code≠0。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-message-it-secret-0123456789abcd"
})
class REQ20261114120102MessageApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 操作者身份：发送者用种子超管 userId=1；收件人/第三人为预置用户（tenant=default 与令牌一致）。 */
    private static final String SENDER_ID = "1";
    private static final long USER_A = 901001L;
    private static final long USER_B = 901002L;
    private static final long USER_C = 901003L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：发送会经 Redis pub/sub 推送在线收件人事件（本类无在线会话，仅需总线可用）。 */
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
        seedUser(USER_A, "req120102-msg-user-a");
        seedUser(USER_B, "req120102-msg-user-b");
        seedUser(USER_C, "req120102-msg-user-c");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_message");
        jdbcTemplate.update("delete from sys_user where id in (?, ?, ?)", USER_A, USER_B, USER_C);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-3：发送后收件人可见；非收件人/发送者不可见 ----------

    @Test
    void should_deliverToRecipientInboxOnly_when_sendMessage() throws Exception {
        String title = "req-120102-deliver-title";
        send(SENDER_ID, title, List.of(USER_A));

        // 收件人收件箱可见且唯一
        JsonNode inboxOfRecipient = inbox(String.valueOf(USER_A), null);
        assertThat(inboxOfRecipient.path("code").asText()).isEqualTo("0");
        assertThat(inboxOfRecipient.path("data").path("records").size())
                .as("收件人收件箱应恰有该消息").isEqualTo(1);
        assertThat(containsRecord(inboxOfRecipient.path("data").path("records"), "title", title)).isTrue();

        // 非收件人不可见
        JsonNode inboxOfThird = inbox(String.valueOf(USER_C), null);
        assertThat(containsRecord(inboxOfThird.path("data").path("records"), "title", title))
                .as("非收件人不应看到该消息").isFalse();

        // 发送者不可见
        JsonNode inboxOfSender = inbox(SENDER_ID, null);
        assertThat(containsRecord(inboxOfSender.path("data").path("records"), "title", title))
                .as("发送者不应在自己的收件箱看到该消息").isFalse();

        // 未读数：仅收件人 +1
        assertThat(unreadOf(String.valueOf(USER_A))).isEqualTo(1L);
        assertThat(unreadOf(String.valueOf(USER_C))).isZero();
        assertThat(unreadOf(SENDER_ID)).isZero();
    }

    // ---------- AC-4：单条已读 → read/read_at 落库、unread-count 递减、isRead 筛选 ----------

    @Test
    void should_persistReadStateAndDecrementUnread_when_markSingleRead() throws Exception {
        send(SENDER_ID, "req-120102-read-title-1", List.of(USER_A));
        send(SENDER_ID, "req-120102-read-title-2", List.of(USER_A));
        assertThat(unreadOf(String.valueOf(USER_A))).isEqualTo(2L);

        long firstId = idOfRecord(inbox(String.valueOf(USER_A), false), "req-120102-read-title-1");

        JsonNode readResult = markRead(String.valueOf(USER_A), firstId);
        assertThat(readResult.path("code").asText()).isEqualTo("0");

        // read/read_at 落库（原生 SQL 断言）
        assertThat(jdbcTemplate.queryForObject(
                "select is_read from sys_message where id=?", Boolean.class, firstId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select read_at from sys_message where id=?", java.sql.Timestamp.class, firstId)).isNotNull();
        long secondId = idOfRecord(inbox(String.valueOf(USER_A), false), "req-120102-read-title-2");
        assertThat(jdbcTemplate.queryForObject(
                "select is_read from sys_message where id=?", Boolean.class, secondId)).isFalse();

        // unread-count 递减；isRead 筛选两侧正确
        assertThat(unreadOf(String.valueOf(USER_A))).isEqualTo(1L);
        assertThat(containsRecord(inbox(String.valueOf(USER_A), true)
                .path("data").path("records"), "id", String.valueOf(firstId))).isTrue();
        assertThat(containsRecord(inbox(String.valueOf(USER_A), false)
                .path("data").path("records"), "id", String.valueOf(firstId)))
                .as("已读消息不应出现在未读筛选中").isFalse();
    }

    // ---------- AC-4：全部已读 → unread-count 清零、全部落库 ----------

    @Test
    void should_clearAllUnread_when_readAll() throws Exception {
        send(SENDER_ID, "req-120102-readall-title-1", List.of(USER_A));
        send(SENDER_ID, "req-120102-readall-title-2", List.of(USER_A));
        assertThat(unreadOf(String.valueOf(USER_A))).isEqualTo(2L);

        JsonNode result = call(String.valueOf(USER_A), Set.of("super_admin"),
                put("/admin/v1/system/messages/read-all"));

        assertThat(result.path("code").asText()).isEqualTo("0");
        assertThat(unreadOf(String.valueOf(USER_A))).as("全部已读后未读数应清零").isZero();
        Long unreadRows = jdbcTemplate.queryForObject(
                "select count(*) from sys_message where recipient_id=? and is_read=false",
                Long.class, USER_A);
        assertThat(unreadRows).isZero();
    }

    // ---------- AC-5：非本人消息 read → MESSAGE_FORBIDDEN ----------

    @Test
    void should_rejectWithMessageForbidden_when_otherUserMarksRead() throws Exception {
        send(SENDER_ID, "req-120102-forbidden-title", List.of(USER_A));
        long messageId = idOfRecord(inbox(String.valueOf(USER_A), false), "req-120102-forbidden-title");

        JsonNode result = markRead(String.valueOf(USER_B), messageId);

        assertThat(result.path("success").asBoolean()).isFalse();
        // wire 错误码=三段式布局码（SystemErrorCodes.MESSAGE_FORBIDDEN，Backend.md 10.5.2）
        assertThat(result.path("code").asText()).isEqualTo("platform-system-09-0002");
        // 他人消息未被标记
        assertThat(jdbcTemplate.queryForObject(
                "select is_read from sys_message where id=?", Boolean.class, messageId)).isFalse();
    }

    // ---------- AC-5 边界：不存在消息 read → MESSAGE_NOT_FOUND ----------

    @Test
    void should_returnMessageNotFound_when_markReadMissingMessage() throws Exception {
        JsonNode result = markRead(String.valueOf(USER_A), 99999999L);

        assertThat(result.path("success").asBoolean()).isFalse();
        // wire 错误码=三段式布局码（SystemErrorCodes.MESSAGE_NOT_FOUND，Backend.md 10.5.2）
        assertThat(result.path("code").asText()).isEqualTo("platform-system-09-0001");
    }

    // ---------- 补充（无 AC 对应）：消息域无权限统一拒绝 ----------

    @Test
    void should_rejectAllMessageEndpoints_when_noPermission() throws Exception {
        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                post("/admin/v1/system/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "recipientIds", List.of(USER_A), "title", "x", "content", "y"))),
                get("/admin/v1/system/messages"),
                get("/admin/v1/system/messages/unread-count"),
                put("/admin/v1/system/messages/{id}/read", "1"),
                put("/admin/v1/system/messages/read-all"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = call("1", Set.of("req120102-unbound-role"), endpoint);
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint.toString()).isFalse();
            assertThat(body.path("code").asText())
                    .as("业务失败以 Result.code≠0 表达（勿断言 4xx）：%s", endpoint.toString())
                    .isNotEqualTo("0");
        }
    }

    // ---------- 支撑 ----------

    private void send(String senderId, String title, List<Long> recipientIds) throws Exception {
        JsonNode body = call(senderId, Set.of("super_admin"),
                post("/admin/v1/system/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "recipientIds", recipientIds, "title", title,
                                "content", "<p>" + title + "</p>"))));
        assertThat(body.path("code").asText()).as("发送站内信应成功").isEqualTo("0");
    }

    /** 收件箱分页查询；isRead 传 null 时不带筛选参数。 */
    private JsonNode inbox(String userId, Boolean isRead) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/v1/system/messages");
        if (isRead != null) {
            request.param("isRead", String.valueOf(isRead));
        }
        return call(userId, Set.of("super_admin"), request);
    }

    private long unreadOf(String userId) throws Exception {
        JsonNode body = call(userId, Set.of("super_admin"),
                get("/admin/v1/system/messages/unread-count"));
        assertThat(body.path("code").asText()).isEqualTo("0");
        return extractUnread(body.path("data"));
    }

    private JsonNode markRead(String userId, long messageId) throws Exception {
        return call(userId, Set.of("super_admin"),
                put("/admin/v1/system/messages/{id}/read", messageId));
    }

    /** unread-count 响应 data 形态双兼容（AMB-7）：裸数值 / {count} / {unreadCount|unread_count}。 */
    private static long extractUnread(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return -1L;
        }
        if (data.isNumber()) {
            return data.asLong();
        }
        for (String field : List.of("count", "unreadCount", "unread_count")) {
            if (data.hasNonNull(field) && data.get(field).isNumber()) {
                return data.get(field).asLong();
            }
        }
        return -1L;
    }

    private long idOfRecord(JsonNode inboxBody, String title) {
        for (JsonNode record : inboxBody.path("data").path("records")) {
            if (title.equals(record.path("title").asText())) {
                return record.path("id").asLong();
            }
        }
        throw new IllegalStateException("收件箱未找到消息：" + title);
    }

    private static boolean containsRecord(JsonNode records, String field, String value) {
        for (JsonNode record : records) {
            if (value.equals(record.path(field).asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode call(String userId, Set<String> roles,
                          AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token(userId, roles))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String token(String userId, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, "default", Channel.B, roles));
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
}
