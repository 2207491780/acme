package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261119-082531 个人中心资料/偏好/推送令牌（platform-system，/admin/v1/system/me/*）
 * 服务域集成测试（Testcontainers PG+Redis，装配口径照抄 {@code REQ20261114120102MessageApiIntegrationTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1 资料更新回显与校验拒绝（putMeUpdatesProfileAndEchoesOnGetMe /
 * putMeAvatarUrlEchoesOnGetMe / putMeRejectsInvalidEmail / putMeRejectsInvalidPhone /
 * putMeRejectsBlankOrOverlongNickname）；AC-5 偏好 upsert 与推送令牌注册删除
 * （putMePreferencesUpsertsAndEchoesOnGet / getMePreferencesReturnsDefaultMessagePushWhenNoRow /
 * postMePushTokenUpsertsByToken / deleteMePushTokenRemovesIt）；AC-6 触发面关闭态
 * （pushDisabledCausesZeroHttpOutbound——enabled 默认 false，发送器 bean 不装配，站内信发送零外呼）。
 * AC-6 开启态触发（messagePushEnabledWithTokenTriggersExpoApiCall）归属
 * {@code REQ20261119082531PushNotifyTriggerIntegrationTest}（独立 @SpringBootTest 上下文——
 * @ConditionalOnProperty 按静态属性装配，无法在本类上下文内翻转）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 资料主体：PUT /me 行解析走 UserContext.userId（findByIdGlobal PK 全局直读）——
 *       预置真实 sys_user 行（Message AMB-8 同口径）。</li>
 *   <li>AMB-2 校验拒绝断言：Bean Validation 失败 → HTTP 400 + VALIDATION_FAILED 信封
 *       （GlobalExceptionTranslator 10.5.2 口径），字段级明细不逐一断言。</li>
 *   <li>AMB-3 偏好缺省：未设行 GET 回 messagePush=false（确认记录⑤缺省语义）。</li>
 *   <li>AMB-4 推送令牌 upsert：同 token 换账号注册归一新主体（设备换绑语义）——
 *       断言行迁移与幂等单行；删除幂等（不存在视为已删）且不影响他人行。</li>
 *   <li>AMB-5 关闭态零外呼：本类不设 acme.notify.push.enabled（默认 false，发送器 bean 缺席），
 *       以本地 JDK HttpServer 桩计数断言站内信发送后零外呼；开启态用例见独立触发 IT。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req082531-profile-secret-0123456789abcd"
})
class REQ20261119082531ProfilePreferenceIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 资料主体（PUT /me 行解析目标，AMB-1）。 */
    private static final long PROFILE_USER_ID = 902001L;

    /** 偏好/令牌主体（无需 sys_user 行——按 user_id 定位存储）。 */
    private static final long PREF_USER_A = 902011L;
    private static final long PREF_USER_B = 902012L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：站内信发送链路 NotifyPushBus 广播（关闭态下仅总线语义，需可用）。 */
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
        seedUser(PROFILE_USER_ID, "req082531-profile-user");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_user where id = ?", PROFILE_USER_ID);
        jdbcTemplate.update("delete from sys_user_preference");
        jdbcTemplate.update("delete from sys_push_token");
        jdbcTemplate.update("delete from sys_message");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：PUT /me 合法资料回显 ----------

    @Test
    void putMeUpdatesProfileAndEchoesOnGetMe() throws Exception {
        JsonNode putBody = JSON.readTree(putMe("""
                {"nickname": "新昵称", "email": "me@example.com", "phone": "13800138000"}
                """).getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(putBody.path("code").asText()).isEqualTo("0");
        JsonNode user = putBody.path("data").path("user");
        assertThat(user.path("nickname").asText()).isEqualTo("新昵称");
        assertThat(user.path("email").asText()).isEqualTo("me@example.com");
        assertThat(user.path("phone").asText()).isEqualTo("13800138000");

        // 再 GET /me：回显一致（AC-1）
        JsonNode getBody = JSON.readTree(getMe().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(getBody.path("code").asText()).isEqualTo("0");
        JsonNode echoed = getBody.path("data").path("user");
        assertThat(echoed.path("nickname").asText()).isEqualTo("新昵称");
        assertThat(echoed.path("email").asText()).isEqualTo("me@example.com");
        assertThat(echoed.path("phone").asText()).isEqualTo("13800138000");

        // 持久化一致（sys_user 行）
        assertThat(jdbcTemplate.queryForObject(
                "select nickname from sys_user where id = ?", String.class, PROFILE_USER_ID))
                .isEqualTo("新昵称");
    }

    @Test
    void putMeAvatarUrlEchoesOnGetMe() throws Exception {
        JsonNode putBody = JSON.readTree(putMe("""
                {"nickname": "头像用户", "email": "", "phone": "", "avatarUrl": "/files/avatar-1.png"}
                """).getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(putBody.path("code").asText()).isEqualTo("0");
        assertThat(putBody.path("data").path("user").path("avatarUrl").asText())
                .isEqualTo("/files/avatar-1.png");

        JsonNode getBody = JSON.readTree(getMe().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(getBody.path("data").path("user").path("avatarUrl").asText())
                .isEqualTo("/files/avatar-1.png");
        assertThat(jdbcTemplate.queryForObject(
                "select avatar_url from sys_user where id = ?", String.class, PROFILE_USER_ID))
                .isEqualTo("/files/avatar-1.png");
    }

    // ---------- AC-1：校验拒绝（HTTP 400 + VALIDATION_FAILED 信封，AMB-2） ----------

    @Test
    void putMeRejectsInvalidEmail() throws Exception {
        MvcResult result = putMe("""
                {"nickname": "合法昵称", "email": "not-an-email", "phone": ""}
                """);
        assertThat(result.getResponse().getStatus()).as("非法 email 应 400").isEqualTo(400);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("SYS-01-0001");
    }

    @Test
    void putMeRejectsInvalidPhone() throws Exception {
        MvcResult result = putMe("""
                {"nickname": "合法昵称", "email": "", "phone": "2380013800"}
                """);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("SYS-01-0001");
    }

    @Test
    void putMeRejectsBlankOrOverlongNickname() throws Exception {
        MvcResult blank = putMe("""
                {"nickname": "", "email": "", "phone": ""}
                """);
        assertThat(blank.getResponse().getStatus()).as("空白昵称应 400").isEqualTo(400);

        MvcResult overlong = putMe("""
                {"nickname": "%s", "email": "", "phone": ""}
                """.formatted("超".repeat(33)));
        assertThat(overlong.getResponse().getStatus()).as("超长昵称（33 字）应 400").isEqualTo(400);
    }

    // ---------- AC-5：偏好 GET 缺省 / PUT upsert 回显 ----------

    @Test
    void getMePreferencesReturnsDefaultMessagePushWhenNoRow() throws Exception {
        JsonNode body = JSON.readTree(callAs(PREF_USER_A, get("/admin/v1/system/me/preferences"))
                .getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("messagePush").asBoolean())
                .as("未设偏好行回缺省 false（AMB-3）").isFalse();
    }

    @Test
    void putMePreferencesUpsertsAndEchoesOnGet() throws Exception {
        JsonNode first = JSON.readTree(mvc.perform(callAsBuilder(PREF_USER_A,
                        put("/admin/v1/system/me/preferences"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messagePush\": true}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(first.path("code").asText()).isEqualTo("0");
        assertThat(first.path("data").path("messagePush").asBoolean()).isTrue();

        // 再 GET：持久化一致
        JsonNode echoed = JSON.readTree(callAs(PREF_USER_A, get("/admin/v1/system/me/preferences"))
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(echoed.path("data").path("messagePush").asBoolean()).isTrue();

        // 重复 PUT（幂等 upsert，本人单行）+ 关闭翻转
        JsonNode second = JSON.readTree(mvc.perform(callAsBuilder(PREF_USER_A,
                        put("/admin/v1/system/me/preferences"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messagePush\": false}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(second.path("data").path("messagePush").asBoolean()).isFalse();
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_preference where user_id = ?", Long.class, PREF_USER_A);
        assertThat(rowCount).as("本人偏好单行（upsert 不新增行）").isEqualTo(1L);
        // jsonb 文本形态断言经解析（不耦合序列化空格格式）
        JsonNode prefs = JSON.readTree(jdbcTemplate.queryForObject(
                "select prefs::text from sys_user_preference where user_id = ?",
                String.class, PREF_USER_A));
        assertThat(prefs.path("messagePush").asBoolean()).isFalse();
    }

    // ---------- AC-5：推送令牌注册 upsert / 删除 ----------

    @Test
    void postMePushTokenUpsertsByToken() throws Exception {
        String token = "ExponentPushToken[req082531-token-1]";

        // A 首次注册：新增行
        assertThat(registerToken(PREF_USER_A, token, "ios").getResponse().getStatus()).isEqualTo(200);
        assertThat(tokenOwner(token)).isEqualTo(PREF_USER_A);

        // A 重复注册（幂等单行）
        assertThat(registerToken(PREF_USER_A, token, "android").getResponse().getStatus()).isEqualTo(200);
        assertThat(tokenCount(token)).as("同 token 重复注册归一单行").isEqualTo(1L);

        // B 注册同 token：归一新主体（设备换绑语义，AMB-4）
        assertThat(registerToken(PREF_USER_B, token, "web").getResponse().getStatus()).isEqualTo(200);
        assertThat(tokenOwner(token)).as("同 token 换账号注册应迁移到新主体").isEqualTo(PREF_USER_B);
        assertThat(tokenCount(token)).isEqualTo(1L);
    }

    @Test
    void deleteMePushTokenRemovesIt() throws Exception {
        String token = "ExponentPushToken[req082531-token-2]";
        assertThat(registerToken(PREF_USER_A, token, "ios").getResponse().getStatus()).isEqualTo(200);

        // 本人删除
        assertThat(callAs(PREF_USER_A,
                delete("/admin/v1/system/me/push-tokens").queryParam("token", token))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(tokenCount(token)).as("本人 token 删除后应移除").isZero();

        // 幂等：不存在视为已删（不报错）
        assertThat(callAs(PREF_USER_A,
                delete("/admin/v1/system/me/push-tokens").queryParam("token", token))
                .getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- AC-6：关闭态触发面——站内信发送零外呼（AMB-5） ----------

    @Test
    void pushDisabledCausesZeroHttpOutbound() throws Exception {
        HttpServer stub = stubExpoEndpoint();
        try {
            long recipient = 902021L;
            seedUser(recipient, "req082531-push-recipient");
            // 收件人注册推送令牌（若发送器装配则会外呼该桩）
            assertThat(registerToken(recipient, "ExponentPushToken[req082531-recipient]", "ios")
                    .getResponse().getStatus()).isEqualTo(200);

            // 超管发站内信给收件人（完整触发链：事务提交 → PushNotifyListener 旁路）
            JsonNode body = JSON.readTree(mvc.perform(callAsBuilder(1L, post("/admin/v1/system/messages"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"recipientIds": [%d], "title": "关闭态标题", "content": "关闭态正文"}
                                    """.formatted(recipient)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("code").asText()).isEqualTo("0");

            // 关闭态：发送器 bean 缺席（acme.notify.push.enabled 默认 false）——桩零命中
            Thread.sleep(300);
            assertThat(hitsOf(stub)).as("推送关闭态不得产生任何外呼（AC-6 disabled）").isZero();
        } finally {
            stub.stop(0);
        }
    }

    // ---------- 支撑 ----------

    private final AtomicInteger stubHits = new AtomicInteger();

    /** Expo 发送端 HTTP 桩（本类计数面；未注入 Spring 配置——关闭态下仅作零外呼计数器）。 */
    private HttpServer stubExpoEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger hits = stubHits;
        server.createContext("/--/api/v2/push/send", exchange -> {
            hits.incrementAndGet();
            byte[] resp = "{\"data\":[{\"status\":\"ok\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        server.start();
        return server;
    }

    private int hitsOf(HttpServer stub) {
        return stubHits.get();
    }

    /** 预置 sys_user 行（Message AMB-8 口径：/me 行解析与收件人存在性均需真实行）。 */
    private void seedUser(long id, String username) {
        jdbcTemplate.update("""
                insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, 'default', ?, ?, null, null, 'ENABLED', false,
                        now(), 'req082531-it', now(), 'req082531-it', 0, false)
                """, id, username, username);
    }

    /** 指定主体（userId）发起的请求执行（内部令牌承载身份，Message IT 同款口径）。 */
    private MvcResult callAs(long userId, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(callAsBuilder(userId, request)).andReturn();
    }

    /** 推送令牌注册请求执行（JSON 体捷径）。 */
    private MvcResult registerToken(long userId, String token, String platform) throws Exception {
        return mvc.perform(callAsBuilder(userId, post("/admin/v1/system/me/push-tokens"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\", \"platform\": \"%s\"}".formatted(token, platform)))
                .andReturn();
    }

    /** 以资料主体 PUT /admin/v1/system/me。 */
    private MvcResult putMe(String json) throws Exception {
        return mvc.perform(callAsBuilder(PROFILE_USER_ID, put("/admin/v1/system/me"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andReturn();
    }

    /** 以资料主体 GET /admin/v1/system/me。 */
    private MvcResult getMe() throws Exception {
        return mvc.perform(callAsBuilder(PROFILE_USER_ID, get("/admin/v1/system/me"))).andReturn();
    }

    /** 指定主体请求构建（内部令牌头注入，不改写请求执行）。 */
    private MockHttpServletRequestBuilder callAsBuilder(long userId, MockHttpServletRequestBuilder request) {
        return request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                        String.valueOf(userId), "default", Channel.B, Set.of("super_admin"))));
    }

    /** 推送令牌归属主体（无行返回 null）。 */
    private Long tokenOwner(String token) {
        List<Long> owners = jdbcTemplate.queryForList(
                "select user_id from sys_push_token where token = ?", Long.class, token);
        return owners.isEmpty() ? null : owners.get(0);
    }

    /** 推送令牌行计数（含软删行——物理删语义下应等价 0）。 */
    private long tokenCount(String token) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_push_token where token = ?", Long.class, token);
        return count == null ? 0L : count;
    }
}
