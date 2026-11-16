package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261117-025200 租户会话批量撤销（platform-auth，POST /internal/v1/tenant-sessions/revocations）
 * 服务域集成测试（Testcontainers PG+Redis，装配口径照抄
 * {@code REQ20261117011514LoginCaptchaPolicyIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1（租户 T 有 2 个 ACTIVE 会话 → 底账全部 KICKED、撤销集写入 isRevoked=true、计数 2/0）；
 * AC-2（SessionRedis.revoke 抛异常 → failedCount 计数、底账已 KICKED、端点仍成功——降级 WARN 不回滚，
 * 确认记录①）；unknown tenant 幂等 0/0（契约 §API）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 会话预置照委派单口径「repository 插 ACTIVE 底账＋SessionRedis.indexSession」：auth_session
 *       直插列集照抄 {@code REQ20261114120105OnlineUsersIntegrationTest}（AMB-5/AMB-7 先例：表名 auth_session、
 *       status='ACTIVE'）；token_id 手工可钉，撤销键/底账断言直接对 token_id。撤销端点按契约从底账
 *       枚举（AuthSessionRepository byTenant 查询），不依赖 Redis 会话信息。</li>
 *   <li>AMB-2 SessionRedis 以 {@code @MockitoSpyBean} 包真 bean（本仓库首次使用）：AC-1 走真实撤销键
 *       写入与 isRevoked 命中（AuthRedisSupportIntegrationTest 已锁 revoke/isRevoked 语义）；
 *       AC-2 仅对 revoke 单方法桩抛异常。若该组件实际不可代理，回退方案=拆分双类（AC-2 改
 *       {@code @MockitoBean} 全桩、AC-1 键存在断言降级为 verify(revoke) 交互断言）。</li>
 *   <li>AMB-3 revoke 的 remaining TTL 口径：按契约「status=ACTIVE 且未过期」以底账 expires_at 推算
 *       （直插行 expires_at=now()+25 分钟保证剩余为正）；若实现改为读 Redis 会话信息推导剩余时长，
 *       直插行的撤销计数语义需 TL 三向复核。</li>
 *   <li>AMB-4 调用方身份模拟 platform-system 服务主体（Q-D08：内部令牌 userId=服务名，同
 *       REQ20261117011514InternalSystemConfigApiTest 先例）；internal 端点无权限点，roles 仅随令牌装配。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req025200-revoke-secret-01234567",
        "acme.auth.token.hmac-secret=req025200-revoke-secret-01234567"
})
class REQ20261117025200TenantSessionRevokeIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TENANT_A = "req025200-tenant-a";
    private static final String TENANT_NONE = "req025200-tenant-none";
    private static final String TOKEN_S1 = "req025200-ta-s1";
    private static final String TOKEN_S2 = "req025200-ta-s2";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：SessionRedis 真实组件（撤销键写入 / isRevoked 命中）需要。 */
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

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** SessionRedis 侦桩（AMB-2）：默认全真实；AC-2 用例内仅对 revoke 桩抛异常。 */
    @MockitoSpyBean
    private SessionRedis sessionRedis;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        jdbcTemplate.update("delete from auth_session where token_id like 'req025200-%'");
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：预置会话底账 + 撤销键 + 会话索引（本类独立容器，残留随容器销毁兜底）
        jdbcTemplate.update("delete from auth_session where token_id like 'req025200-%'");
        redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + TOKEN_S1);
        redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + TOKEN_S2);
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "2");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：租户 T 两 ACTIVE 会话 → 底账全 KICKED、撤销集写入、计数 2/0 ----------

    @Test
    void should_revokeAllActiveSessionsAndWriteRevocationKeys_when_tenantRevocationRequested() {
        seedActiveSession(925101L, TOKEN_S1, 1L, TENANT_A);
        seedActiveSession(925102L, TOKEN_S2, 2L, TENANT_A);
        sessionRedis.indexSession(1L, TOKEN_S1, 600);
        sessionRedis.indexSession(2L, TOKEN_S2, 600);

        JsonNode body = callRevocation(TENANT_A);

        assertThat(body.path("code").asText()).as("撤销端点幂等成功语义").isEqualTo("0");
        assertThat(body.path("data").path("revokedCount").asInt())
                .as("撤销集写入成功数（int 计数，TenantSessionRevokeView）").isEqualTo(2);
        assertThat(body.path("data").path("failedCount").asInt()).isZero();

        assertThat(sessionStatus(TOKEN_S1)).as("底账 KICKED（事实源，确认记录①）").isEqualTo("KICKED");
        assertThat(sessionStatus(TOKEN_S2)).isEqualTo("KICKED");
        assertThat(sessionRedis.isRevoked(TOKEN_S1))
                .as("撤销集写入：auth:revoke:{jti} 键存在（网关 RevocationCheckFilter 据此 401）").isTrue();
        assertThat(sessionRedis.isRevoked(TOKEN_S2)).isTrue();
    }

    // ---------- 契约 §API：unknown tenant → revokedCount=0 成功返回（幂等） ----------

    @Test
    void should_returnEmptyCounts_when_unknownTenantRevocationRequested() {
        seedActiveSession(925201L, TOKEN_S1, 1L, TENANT_A);

        JsonNode body = callRevocation(TENANT_NONE);

        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("revokedCount").asInt())
                .as("无该租户会话 → 幂等空结果 0/0").isZero();
        assertThat(body.path("data").path("failedCount").asInt()).isZero();
        assertThat(sessionStatus(TOKEN_S1))
                .as("未知租户撤销不得波及其他租户会话").isEqualTo("ACTIVE");
        assertThat(sessionRedis.isRevoked(TOKEN_S1)).isFalse();
    }

    // ---------- AC-2：revoke 写入抛异常 → failedCount 计数、底账仍 KICKED、端点成功（降级不回滚） ----------

    @Test
    void should_countFailuresAndKeepLedgerKicked_when_sessionRedisRevokeFails() {
        seedActiveSession(925301L, TOKEN_S1, 1L, TENANT_A);
        seedActiveSession(925302L, TOKEN_S2, 2L, TENANT_A);
        doThrow(new RuntimeException("req025200-revoke-down"))
                .when(sessionRedis).revoke(anyString(), any());

        JsonNode body = callRevocation(TENANT_A);

        assertThat(body.path("code").asText())
                .as("降级口径：接口不报错，失败进 failedCount（确认记录①，WARN 不断言）").isEqualTo("0");
        assertThat(body.path("data").path("revokedCount").asInt()).isZero();
        assertThat(body.path("data").path("failedCount").asInt())
                .as("逐会话撤销失败计数").isEqualTo(2);

        assertThat(sessionStatus(TOKEN_S1))
                .as("DB 为事实源：撤销键写入失败不回滚底账 KICKED").isEqualTo("KICKED");
        assertThat(sessionStatus(TOKEN_S2)).isEqualTo("KICKED");
        assertThat(sessionRedis.isRevoked(TOKEN_S1)).as("写入失败则撤销键不存在").isFalse();
        assertThat(sessionRedis.isRevoked(TOKEN_S2)).isFalse();
    }

    // ---------- 支撑 ----------

    /** 直插 ACTIVE 底账（OnlineUsers AMB-5/AMB-7 列集先例；expires_at 未来时=未过期会话）。 */
    private void seedActiveSession(long id, String tokenId, long userId, String tenantId) {
        jdbcTemplate.update("""
                insert into auth_session (id, token_id, user_id, tenant_id, channel, status, issued_at, expires_at,
                                          created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, 'B', 'ACTIVE', now(), now() + interval '25 minutes',
                        now(), 'req025200-it', now(), 'req025200-it', 0, false)
                """, id, tokenId, userId, tenantId);
    }

    private String sessionStatus(String tokenId) {
        return jdbcTemplate.queryForObject(
                "select status from auth_session where token_id = ?", String.class, tokenId);
    }

    /** platform-system 服务主体调用（Q-D08，AMB-4）；internal 端点业务语义恒 HTTP 200 信封。 */
    private JsonNode callRevocation(String tenantCode) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("tenantCode", tenantCode);
        MockHttpServletRequestBuilder request = post("/internal/v1/tenant-sessions/revocations")
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                        "platform-system", "default", Channel.B, Set.of("super_admin"))))
                .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "dev-1", "1.0"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(requestBody));
        try {
            MvcResult result = mvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("internal 撤销端点业务失败也走统一信封（勿 4xx）").isEqualTo(200);
            return bodyOf(result);
        } catch (Exception ex) {
            throw new IllegalStateException("撤销请求失败：tenantCode=" + tenantCode, ex);
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("请求体序列化失败", ex);
        }
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
