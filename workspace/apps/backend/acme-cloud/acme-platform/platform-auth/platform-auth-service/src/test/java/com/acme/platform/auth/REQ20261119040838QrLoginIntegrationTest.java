package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.PlatformAuthApplication;
import com.acme.platform.auth.api.AuthErrorCodes;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.redis.QrTicketStore;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261119-040838 APP 扫码登录（platform-auth，/admin/v1/auth/qr-tickets**）服务域集成测试
 * （Testcontainers PG+Redis，装配口径照抄 {@code REQ20261117025200TenantSwitchIntegrationTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1（创建 200＋qrPayload/expiresInSeconds=120＋同 IP 第 11 次 QR_TICKET_LIMITED 429）；
 * AC-2（B 令牌 info created→scanned 幂等＋未知 id QR_TICKET_EXPIRED）；AC-3（confirm 全链：
 * AdminTokenIssued 确认者令牌、auth_session 底账 B 通道 ACTIVE、旧 B 会话互踢 KICKED＋撤销集、
 * 登录日志落库）；AC-4（二次 confirm QR_TICKET_INVALID＋票据保留、轮询 confirmed token 非空且
 * JWS claims 可用）；AC-5（Redis 删键模拟 TTL 过期 → 轮询 expired＋info/confirm EXPIRED；
 * 取消 → cancelled＋info/confirm CANCELLED＋cancel 幂等）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 确认者身份经内部令牌承载（TenantSwitch IT 范本口径）：info/confirm/cancel 请求
 *       X-Acme-Token=内部令牌（userId/tenant/roles），InternalTokenAuthFilter 绑定
 *       UserContext/TenantContext——即 B 端确认者本人（确认记录①）。</li>
 *   <li>AMB-2 公开端点（创建/轮询）经 MockMvc 过滤器链以服务侧 public-paths 同款两豁免配置放行
 *       （精确创建路径 + 单段通配轮询）；info/confirm/cancel 双段后缀不豁免 → 无令牌 401。</li>
 *   <li>AMB-3 画像桩 {@code @MockitoBean AuthProfileGateway}（platform-system 不在本测试上下文，
 *       LoginLog IT 同口径）：username=admin 供 AdminTokenIssued/登录日志/confirmUsername 断言。</li>
 *   <li>AMB-4 频控计数跨用例残留：{@code auth:qr:limit:127.0.0.1} 在 BeforeEach/AfterEach 清理
 *       （MockMvc remoteAddr 恒定）；票据键 {@code auth:qr:ticket:*} 同步清理。</li>
 *   <li>AMB-5 TTL 过期模拟为直接删键（120s 真等价于 Redis TTL 失效——键缺失即 expired 语义）。</li>
 *   <li>AMB-6 登录日志为异步落库：断言轮询（5s 上限，LoginLog IT 同口径），同步实现亦兼容。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req040838-qr-secret-0123456789ab",
        "acme.auth.token.hmac-secret=req040838-qr-secret-0123456789ab"
})
class REQ20261119040838QrLoginIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OLD_TOKEN = "req040838-old-token";

    /** 服务侧公开豁免（AMB-2）：与 test yml acme.security.public-paths 扫码两处一致。 */
    private static final List<String> QR_PUBLIC_PATHS =
            List.of("/admin/v1/auth/qr-tickets", "/admin/v1/auth/qr-tickets/*");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：QrTicketStore / SessionRedis 真实组件（票据状态机 + 会话索引/撤销集）需要。 */
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
    private UserTokenIssuer userTokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SessionRedis sessionRedis;

    /** 画像桩（AMB-3）：AdminTokenIssued.username / 登录日志 username / confirmUsername 数据源。 */
    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, QR_PUBLIC_PATHS))
                .build();
        when(authProfileGateway.findByUserIds(any())).thenReturn(Map.of(
                1L, new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin"))));
        jdbcTemplate.update("delete from auth_session where token_id like 'req040838-%' or user_id = 1");
        jdbcTemplate.update("delete from sys_login_log");
        clearQrRedisKeys();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：预置/新签会话底账 + 登录日志 + 票据/频控键 + 撤销键 + 会话索引
        jdbcTemplate.update("delete from auth_session where token_id like 'req040838-%' or user_id = 1");
        jdbcTemplate.update("delete from sys_login_log");
        clearQrRedisKeys();
        redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + OLD_TOKEN);
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        TenantContext.clear();
        UserContext.clear();
    }

    private void clearQrRedisKeys() {
        deleteByPrefix(QrTicketStore.KEY_PREFIX);
        deleteByPrefix(QrTicketStore.LIMIT_KEY_PREFIX);
    }

    private void deleteByPrefix(String prefix) {
        var keys = redisTemplate.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ---------- AC-1：创建 200（ticketId/qrPayload/120s）＋同 IP 第 11 次 QR_TICKET_LIMITED ----------

    @Test
    void should_returnTicketWithPayload_and_limitEleventhCreationPerMinuteFromSameIp() {
        JsonNode first = bodyOf(performCreate());
        assertThat(first.path("code").asText()).as("首次创建成功").isEqualTo("0");
        JsonNode data = first.path("data");
        assertThat(data.path("ticketId").asText()).isNotBlank();
        assertThat(data.path("qrPayload").asText())
                .as("二维码内容=紧凑 JSON {\"t\":ticketId}（确认记录⑤）")
                .isEqualTo("{\"t\":\"" + data.path("ticketId").asText() + "\"}");
        assertThat(data.path("expiresInSeconds").asInt()).as("TTL 120s（确认记录③）").isEqualTo(120);

        // 第 2~10 次创建放行，第 11 次 → QR_TICKET_LIMITED（429 限流语义，确认记录④）
        for (int i = 2; i <= 10; i++) {
            assertThat(bodyOf(performCreate()).path("code").asText())
                    .as("第 %d 次创建放行".formatted(i)).isEqualTo("0");
        }
        MvcResult eleventh = performCreate();
        assertThat(eleventh.getResponse().getStatus()).as("频控 429").isEqualTo(429);
        JsonNode limited = bodyOf(eleventh);
        assertThat(limited.path("success").asBoolean()).isFalse();
        assertThat(limited.path("code").asText()).isEqualTo(AuthErrorCodes.QR_TICKET_LIMITED);
    }

    // ---------- AC-2：info created→scanned 幂等 + 未知 id EXPIRED ----------

    @Test
    void should_markScannedIdempotently_when_infoCalledRepeatedly() {
        String ticketId = createTicket();

        JsonNode first = bodyOf(performInfo(ticketId));
        assertThat(first.path("code").asText()).isEqualTo("0");
        assertThat(first.path("data").path("status").asText()).as("扫码推进 created→scanned").isEqualTo("scanned");
        assertThat(first.path("data").path("createdAt").asText()).as("createdAt 展示字段非空").isNotBlank();

        JsonNode second = bodyOf(performInfo(ticketId));
        assertThat(second.path("code").asText()).as("重复 info 幂等成功").isEqualTo("0");
        assertThat(second.path("data").path("status").asText()).as("重复 info 不回退（确认记录③）").isEqualTo("scanned");
    }

    @Test
    void should_rejectExpired_when_infoOnUnknownTicket() {
        // AC-2/AC-5：未知 id（缺失=TTL 兜底语义）→ QR_TICKET_EXPIRED 业务失败信封
        JsonNode body = bodyOf(performInfo("req040838-unknown"));

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(AuthErrorCodes.QR_TICKET_EXPIRED);
    }

    // ---------- AC-3：confirm 全链（令牌 / 底账 B 通道 ACTIVE / 互踢 / 登录日志） ----------

    @Test
    void should_issueConfirmerTokenAndKickOldSessions_when_confirmOnScannedTicket() {
        seedActiveSession(9408381L, OLD_TOKEN, 1L, "default");
        sessionRedis.indexSession(1L, OLD_TOKEN, 900);
        String ticketId = createTicket();
        assertThat(bodyOf(performInfo(ticketId)).path("data").path("status").asText()).isEqualTo("scanned");

        MvcResult result = performConfirm(ticketId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        JsonNode data = body.path("data");
        // AdminTokenIssued 形状（确认记录①：token=确认者本人；wire userId 为 string，同 login）
        assertThat(data.path("accessToken").asText()).as("签发确认者访问令牌").isNotBlank();
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.path("userId").isTextual()).as("userId wire 为字符串（Long→String 模块）").isTrue();
        assertThat(data.path("userId").asText()).isEqualTo("1");
        assertThat(data.path("username").asText()).isEqualTo("admin");

        // 旧 B 会话互踢：底账 KICKED + 撤销集写入（AC-3）
        assertThat(sessionStatus(OLD_TOKEN)).as("旧会话底账 KICKED").isEqualTo("KICKED");
        assertThat(sessionRedis.isRevoked(OLD_TOKEN)).as("旧令牌入撤销集").isTrue();

        // 新会话底账：channel=B、ACTIVE，Redis 单会话索引指向新会话
        List<Map<String, Object>> newRows = jdbcTemplate.queryForList(
                "select token_id from auth_session where user_id = 1 and channel = 'B' and status = 'ACTIVE'");
        assertThat(newRows).as("确认后恰好一条 ACTIVE B 会话底账").hasSize(1);
        String newTokenId = String.valueOf(newRows.get(0).get("token_id"));
        assertThat(sessionRedis.currentSessionTokenId(1L)).as("会话索引更新为新会话").isEqualTo(newTokenId);

        // 登录日志（channel B 成功，AMB-6 异步轮询）
        awaitLogRow("username = 'admin' and user_type = 'B' and fail_reason is null");

        // 签发令牌 JWS claims（AC-4 校验同一签发结果可用）：channel=B / tokenType=access / jti=底账 tokenId
        var verified = userTokenIssuer.verify(data.path("accessToken").asText());
        assertThat(verified.channel()).isEqualTo(UserTokenIssuer.CHANNEL_B);
        assertThat(verified.tokenType()).isEqualTo(UserTokenIssuer.TOKEN_TYPE_ACCESS);
        assertThat(verified.tokenId()).as("jti=会话底账 tokenId").isEqualTo(newTokenId);
    }

    // ---------- AC-4：二次 confirm INVALID＋票据保留、轮询 confirmed token 非空 ----------

    @Test
    void should_rejectSecondConfirm_and_keepConfirmedTokenPollable() {
        String ticketId = createTicket();
        performInfo(ticketId);
        JsonNode confirmed = bodyOf(performConfirm(ticketId));
        assertThat(confirmed.path("code").asText()).as("首次确认成功前置").isEqualTo("0");

        // 二次 confirm → QR_TICKET_INVALID（票据保留，不被失败消费）
        MvcResult second = performConfirm(ticketId);
        assertThat(second.getResponse().getStatus()).as("INVALID 为业务失败信封 HTTP 200").isEqualTo(200);
        JsonNode rejected = bodyOf(second);
        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText()).isEqualTo(AuthErrorCodes.QR_TICKET_INVALID);

        // 票据保留：轮询 confirmed＋token 非空（TTL 内可重复取，确认记录④）
        assertThat(redisTemplate.hasKey(QrTicketStore.KEY_PREFIX + ticketId)).as("票据未被二次确认消费").isTrue();
        JsonNode poll = bodyOf(performPoll(ticketId));
        assertThat(poll.path("data").path("status").asText()).isEqualTo("confirmed");
        assertThat(poll.path("data").path("token").path("accessToken").asText())
                .as("轮询 token 与首次确认同源").isEqualTo(confirmed.path("data").path("accessToken").asText());
    }

    // ---------- AC-5：过期（删键模拟 TTL）＋取消 ----------

    @Test
    void should_pollExpiredAndRejectInfoConfirm_when_ticketDeletedByTtl() {
        // AMB-5：直接删键等价 TTL 自然失效（键缺失即 expired 语义）
        String ticketId = createTicket();
        redisTemplate.delete(QrTicketStore.KEY_PREFIX + ticketId);

        JsonNode poll = bodyOf(performPoll(ticketId));
        assertThat(poll.path("code").asText()).as("轮询不失败（只读）").isEqualTo("0");
        assertThat(poll.path("data").path("status").asText()).as("过期统一 expired").isEqualTo("expired");
        assertThat(poll.path("data").path("token").isNull()).as("expired 态 token=null").isTrue();

        assertThat(bodyOf(performInfo(ticketId)).path("code").asText()).isEqualTo(AuthErrorCodes.QR_TICKET_EXPIRED);
        assertThat(bodyOf(performConfirm(ticketId)).path("code").asText()).isEqualTo(AuthErrorCodes.QR_TICKET_EXPIRED);
    }

    @Test
    void should_markCancelledAndRejectInfoConfirm_when_cancelCalled() {
        String ticketId = createTicket();

        assertThat(bodyOf(performCancel(ticketId)).path("code").asText()).as("取消成功").isEqualTo("0");

        JsonNode poll = bodyOf(performPoll(ticketId));
        assertThat(poll.path("data").path("status").asText()).as("轮询返回 cancelled").isEqualTo("cancelled");
        assertThat(poll.path("data").path("token").isNull()).isTrue();

        assertThat(bodyOf(performInfo(ticketId)).path("code").asText()).isEqualTo(AuthErrorCodes.QR_TICKET_CANCELLED);
        assertThat(bodyOf(performConfirm(ticketId)).path("code").asText()).isEqualTo(AuthErrorCodes.QR_TICKET_CANCELLED);
        assertThat(bodyOf(performCancel(ticketId)).path("code").asText()).as("重复取消幂等成功").isEqualTo("0");
    }

    // ---------- 支撑 ----------

    private void seedActiveSession(long id, String tokenId, long userId, String tenantId) {
        jdbcTemplate.update("""
                insert into auth_session (id, token_id, user_id, tenant_id, channel, status, issued_at, expires_at,
                                          created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, 'B', 'ACTIVE', now(), now() + interval '25 minutes',
                        now(), 'req040838-it', now(), 'req040838-it', 0, false)
                """, id, tokenId, userId, tenantId);
    }

    private String createTicket() {
        JsonNode body = bodyOf(performCreate());
        assertThat(body.path("code").asText()).as("票据创建前置").isEqualTo("0");
        return body.path("data").path("ticketId").asText();
    }

    /** 创建（公开端点，AMB-2：无内部令牌直连）。 */
    private MvcResult performCreate() {
        return perform(post("/admin/v1/auth/qr-tickets"));
    }

    /** 轮询（公开端点，只读）。 */
    private MvcResult performPoll(String ticketId) {
        return perform(get("/admin/v1/auth/qr-tickets/{ticketId}", ticketId));
    }

    /** 扫码取信息（认证端点，AMB-1：确认者内部令牌）。 */
    private MvcResult performInfo(String ticketId) {
        return perform(authenticated(get("/admin/v1/auth/qr-tickets/{ticketId}/info", ticketId)));
    }

    /** 确认（认证端点）。 */
    private MvcResult performConfirm(String ticketId) {
        return perform(authenticated(post("/admin/v1/auth/qr-tickets/{ticketId}/confirm", ticketId)));
    }

    /** 取消（认证端点）。 */
    private MvcResult performCancel(String ticketId) {
        return perform(authenticated(post("/admin/v1/auth/qr-tickets/{ticketId}/cancel", ticketId)));
    }

    /** 操作者身份经内部令牌承载（AMB-1，TenantSwitch IT 范本口径）。 */
    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(
                        new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) {
        try {
            return mvc.perform(request
                    .requestAttr(com.acme.framework.core.support.ClientContextDraft.ATTRIBUTE_NAME,
                            new com.acme.framework.core.support.ClientContextDraft(null, "dev-1", "1.0"))
                    .contentType(MediaType.APPLICATION_JSON)).andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException("请求执行失败：" + request, ex);
        }
    }

    private String sessionStatus(String tokenId) {
        return jdbcTemplate.queryForObject(
                "select status from auth_session where token_id = ?", String.class, tokenId);
    }

    private void awaitLogRow(String where) {
        awaitTrue(Duration.ofSeconds(5), () -> {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from sys_login_log where " + where, Long.class);
            return count != null && count > 0;
        });
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("轮询被中断", ex);
            }
        }
        throw new IllegalStateException("等待条件超时（%ds）".formatted((int) timeout.getSeconds()));
    }

    private JsonNode bodyOf(MvcResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("响应体解析失败", ex);
        }
    }
}
