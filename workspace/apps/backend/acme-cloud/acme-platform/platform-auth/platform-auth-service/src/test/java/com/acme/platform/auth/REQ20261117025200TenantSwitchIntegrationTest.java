package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.api.AuthErrorCodes;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.AuthorizationGateway;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.api.TenantClient;
import com.acme.platform.system.api.TenantInternalView;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261117-025200 超管跨租户切换（platform-auth，POST /admin/v1/auth/tenant-switch）
 * 服务域集成测试（Testcontainers PG+Redis，装配口径照抄
 * {@code REQ20261117011514LoginCaptchaPolicyIntegrationTest} / {@code REQ20261114120105OnlineUsersIntegrationTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-6（超管切换 → 200、AdminTokenIssued 形状、旧会话 KICKED＋撤销集、新底账 tenant_id=目标）；
 * AC-7（非超管 → HTTP 403 权限语义、不产生新会话）；目标租户 DISABLED / 已过期 / 未知 →
 * TENANT_SWITCH_FAILED 统一口径（不泄露目标租户存在性差异，AuthErrorCodes 05 段）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 超管会话预置照既有在线用户集成测试手法（委派单指定）：auth_session 直插 ACTIVE 底账
 *       （token_id 手工可钉）＋ SessionRedis.indexSession——切换按 userId 全量互踢（kickActiveSessions），
 *       底账枚举不依赖登录链路。</li>
 *   <li>AMB-2 操作者身份经内部令牌 roles 承载（范本口径）：请求 X-Acme-Token=内部令牌（userId/tenant/
 *       roles），切换前置校验「以 UserContext roles 为查询键，经 system 内部鉴权面实查内置角色」
 *       （确认记录④）。</li>
 *   <li>AMB-3 内置角色实查桩：{@code AuthorizationGateway.currentBuiltinRoles()} 返回 Set&lt;String&gt;——
 *       方法名镜像 system-api {@code AuthorizationClient#currentBuiltinRoles()}（GET
 *       /internal/v1/authorization/builtin-roles，契约层已落盘）；若网关交付名不同，桩名同步调整。</li>
 *   <li>AMB-4 by-code 桩：{@code TenantClient.findByCode(tenantCode)} 返回
 *       ContractResult&lt;TenantInternalView&gt;（契约层已落盘）；未知租户按 framework-feign 错误解码
 *       语义桩为抛 BizException（TENANT_NOT_FOUND 镜像）。</li>
 *   <li>AMB-5 {@code AuthProfileGateway.findByUserIds} 桩为 AdminTokenIssued.username 来源保险
 *       （OnlineUsers 批量画像先例）；nickname 来源不可钉（画像或凭据种子），不做断言。</li>
 *   <li>AMB-6 目标租户三态失败为业务失败信封（code=TENANT_SWITCH_FAILED），HTTP 状态不做断言
 *       （「业务错误=统一响应体 code≠0，勿断言 4xx」口径；05 段未钉 HTTP 映射）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req025200-switch-secret-01234567",
        "acme.auth.token.hmac-secret=req025200-switch-secret-01234567"
})
class REQ20261117025200TenantSwitchIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TARGET = "req025200-target";
    private static final String OLD_TOKEN = "req025200-old-token";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：SessionRedis 真实组件（旧会话撤销键 / 新会话索引）需要。 */
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

    @Autowired
    private SessionRedis sessionRedis;

    /** 内置角色实查桩（AMB-3）：默认空集=非超管；AC-6 与目标租户三态用例内改桩超管。 */
    @MockitoBean
    private AuthorizationGateway authorizationGateway;

    /** by-code 目标租户桩（AMB-4）：未知租户以抛 BizException 表达（framework-feign 错误解码语义）。 */
    @MockitoBean
    private TenantClient tenantClient;

    /** 批量画像桩（AMB-5）：AdminTokenIssued.username 来源保险。 */
    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        when(authProfileGateway.findByUserIds(any())).thenReturn(Map.of(
                1L, new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin"))));
        jdbcTemplate.update("delete from auth_session where token_id like 'req025200-%' "
                + "or tenant_id like 'req025200-%'");
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：预置/新签会话底账 + 撤销键 + 会话索引（独立容器兜底）
        jdbcTemplate.update("delete from auth_session where token_id like 'req025200-%' "
                + "or tenant_id like 'req025200-%'");
        redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + OLD_TOKEN);
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "2");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-6：超管切换 → 新令牌（目标租户）、旧会话 KICKED＋撤销、新会话底账 ----------

    @Test
    void should_issueTargetTenantTokenAndRevokeOldSessions_when_superAdminSwitchesTenant() {
        seedActiveSession(925401L, OLD_TOKEN, 1L, "default");
        sessionRedis.indexSession(1L, OLD_TOKEN, 900);
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        when(tenantClient.findByCode(TARGET)).thenReturn(okEnvelope(new TenantInternalView(
                TARGET, "目标租户", "ENABLED", null)));

        MvcResult result = performSwitch(TARGET, "1", "default", Set.of("super_admin"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");

        // AdminTokenIssued 形状（wire userId 为 string，同 login；tokenType 固定 Bearer）
        JsonNode data = body.path("data");
        assertThat(data.path("accessToken").asText()).as("签发新访问令牌").isNotBlank();
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.path("expiresAt").asText()).as("过期时间（短会话 Instant wire）").isNotBlank();
        assertThat(data.path("userId").isTextual()).as("userId wire 为字符串（Long→String 模块）").isTrue();
        assertThat(data.path("userId").asText()).isEqualTo("1");
        assertThat(data.path("username").asText()).as("username 来源（画像桩/凭据种子）均为 admin").isEqualTo("admin");

        // 旧会话：底账 KICKED + 撤销集写入（isRevoked=true）
        assertThat(sessionStatus(OLD_TOKEN)).as("旧会话底账 KICKED").isEqualTo("KICKED");
        assertThat(sessionRedis.isRevoked(OLD_TOKEN)).as("旧令牌入撤销集").isTrue();

        // 新会话底账：tenant_id=目标租户、ACTIVE，且 Redis 索引指向新会话
        List<Map<String, Object>> newRows = jdbcTemplate.queryForList(
                "select token_id from auth_session where user_id = 1 and tenant_id = ? and status = 'ACTIVE'", TARGET);
        assertThat(newRows).as("目标租户新会话底账恰好一条").hasSize(1);
        String newTokenId = String.valueOf(newRows.get(0).get("token_id"));
        assertThat(newTokenId).as("新会话令牌不同于旧令牌").isNotEqualTo(OLD_TOKEN);
        assertThat(sessionRedis.currentSessionTokenId(1L))
                .as("会话索引更新为新会话").isEqualTo(newTokenId);
    }

    // ---------- AC-7：非超管 → 403 拒绝且不产生新会话 ----------

    @Test
    void should_rejectWith403AndNoNewSession_when_operatorLacksBuiltInSuperAdmin() {
        seedActiveSession(925402L, OLD_TOKEN, 1L, "default");
        sessionRedis.indexSession(1L, OLD_TOKEN, 900);
        // 未桩内置角色 → 桩默认空集：操作者（demo_operator）不持内置 super_admin
        // 目标租户桩为防御性前置（若实现先校验目标租户后校验角色，仍应落到 403 权限语义）
        when(tenantClient.findByCode(TARGET)).thenReturn(okEnvelope(new TenantInternalView(
                TARGET, "目标租户", "ENABLED", null)));

        MvcResult result = performSwitch(TARGET, "2", "default", Set.of("demo_operator"));

        assertThat(result.getResponse().getStatus())
                .as("非超管 403 权限语义（AuthErrorCodes 05 段）").isEqualTo(403);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo(AuthErrorCodes.TENANT_SWITCH_FORBIDDEN);

        assertThat(countSessions("user_id = 2")).as("不产生新会话底账").isZero();
        assertThat(sessionStatus(OLD_TOKEN)).as("拒绝路径不得波及既有会话").isEqualTo("ACTIVE");
        assertThat(sessionRedis.isRevoked(OLD_TOKEN)).isFalse();
    }

    // ---------- 目标租户不可用三态：统一 TENANT_SWITCH_FAILED（不泄露存在性差异） ----------

    @Test
    void should_rejectWithSwitchFailed_when_targetTenantDisabled() {
        stubSuperAdminAndTarget(new TenantInternalView(TARGET, "目标租户", "DISABLED", null));

        JsonNode body = bodyOf(performSwitch(TARGET, "1", "default", Set.of("super_admin")));

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(AuthErrorCodes.TENANT_SWITCH_FAILED);
        assertOldSessionUntouched();
    }

    @Test
    void should_rejectWithSwitchFailed_when_targetTenantExpired() {
        stubSuperAdminAndTarget(new TenantInternalView(TARGET, "目标租户", "ENABLED",
                Instant.now().minusSeconds(3600)));

        JsonNode body = bodyOf(performSwitch(TARGET, "1", "default", Set.of("super_admin")));

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText())
                .as("已过期目标租户与停用/未知统一口径").isEqualTo(AuthErrorCodes.TENANT_SWITCH_FAILED);
        assertOldSessionUntouched();
    }

    @Test
    void should_rejectWithSwitchFailed_when_targetTenantUnknown() {
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        // 未知租户：system 返回 404 错误信封 → framework-feign 解码为 BizException（AMB-4 镜像）
        when(tenantClient.findByCode(TARGET)).thenThrow(new BizException(new TenantNotFound()));

        JsonNode body = bodyOf(performSwitch(TARGET, "1", "default", Set.of("super_admin")));

        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText())
                .as("未知目标租户与停用/过期统一口径").isEqualTo(AuthErrorCodes.TENANT_SWITCH_FAILED);
        assertOldSessionUntouched();
    }

    // ---------- 支撑 ----------

    /** 镜像 AuthorizationClient 契约：未知租户 404 信封经 Feign 错误解码为 BizException。 */
    private static final class TenantNotFound implements ErrorCode, HttpStatusAware {
        @Override
        public String code() {
            return SystemErrorCodes.TENANT_NOT_FOUND;
        }

        @Override
        public String defaultMessage() {
            return "租户不存在";
        }

        @Override
        public int httpStatus() {
            return 404;
        }
    }

    private void stubSuperAdminAndTarget(TenantInternalView view) {
        when(authorizationGateway.currentBuiltinRoles()).thenReturn(Set.of("super_admin"));
        when(tenantClient.findByCode(TARGET)).thenReturn(okEnvelope(view));
    }

    /** 失败路径公共断言：前置校验拒绝 → 旧会话原样保留、无目标租户新底账。 */
    private void assertOldSessionUntouched() {
        assertThat(sessionStatus(OLD_TOKEN))
                .as("切换失败不得踢出既有会话").isEqualTo("ACTIVE");
        assertThat(sessionRedis.isRevoked(OLD_TOKEN)).isFalse();
        assertThat(countSessions("tenant_id = '" + TARGET + "' and status = 'ACTIVE'"))
                .as("切换失败不得产生目标租户新会话").isZero();
    }

    private long countSessions(String where) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_session where " + where, Long.class);
        return count == null ? 0L : count;
    }

    private String sessionStatus(String tokenId) {
        return jdbcTemplate.queryForObject(
                "select status from auth_session where token_id = ?", String.class, tokenId);
    }

    private static ContractResult<TenantInternalView> okEnvelope(TenantInternalView view) {
        return new ContractResult<>(true, "0", "ok", view);
    }

    /** 直插 ACTIVE 底账（OnlineUsers AMB-5/AMB-7 列集先例）。 */
    private void seedActiveSession(long id, String tokenId, long userId, String tenantId) {
        jdbcTemplate.update("""
                insert into auth_session (id, token_id, user_id, tenant_id, channel, status, issued_at, expires_at,
                                          created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, 'B', 'ACTIVE', now(), now() + interval '25 minutes',
                        now(), 'req025200-it', now(), 'req025200-it', 0, false)
                """, id, tokenId, userId, tenantId);
    }

    /** 操作者身份经内部令牌承载（AMB-2，OnlineUsers 范本口径）。 */
    private MvcResult performSwitch(String targetTenantCode, String userId, String tenantId, Set<String> roles) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("targetTenantCode", targetTenantCode);
        MockHttpServletRequestBuilder request;
        try {
            request = post("/admin/v1/auth/tenant-switch")
                    .header("X-Acme-Channel", "B")
                    .header("X-Acme-Token", tokenIssuer.issue(
                            new AuthenticatedSubject(userId, tenantId, Channel.B, roles)))
                    .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                            new ClientContextDraft(null, "dev-1", "1.0"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(requestBody));
        } catch (Exception ex) {
            throw new IllegalStateException("请求体序列化失败", ex);
        }
        try {
            return mvc.perform(request).andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException("切换请求失败：targetTenantCode=" + targetTenantCode, ex);
        }
    }

    private JsonNode bodyOf(MvcResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("响应体解析失败", ex);
        }
    }
}
