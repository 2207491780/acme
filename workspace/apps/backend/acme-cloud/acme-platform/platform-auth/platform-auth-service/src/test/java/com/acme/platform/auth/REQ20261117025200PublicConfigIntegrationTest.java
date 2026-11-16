package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.api.AuthErrorCodes;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.ContractResult;
import com.acme.platform.system.api.TenantClient;
import com.acme.platform.system.api.TenantInternalView;
import com.acme.platform.system.api.TenantPublicInternalView;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261117-025200 公开站点配置（platform-auth，GET /admin/v1/auth/public-config）与过期租户
 * 登录拒绝（确认记录③）服务域集成测试（Testcontainers PG+Redis，装配口径照抄
 * {@code REQ20261117011514LoginCaptchaPolicyIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-9（未认证 public-config?tenantCode=X 三态：有行按行值 / 无行缺省 null·false /
 * 空白 tenantCode 400；另含契约 §API fail-open——system 不可用 WARN＋缺省返回）；
 * AC-5 后半（租户 expiresAt 已过期 → 直接登录统一 LOGIN_FAILED、不产生会话底账）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 public-view 网关桩：{@code SystemConfigGateway.findPublicView(String tenantCode)} 返回
 *       {@code com.acme.platform.system.api.TenantPublicInternalView}——方法名镜像 system-api
 *       {@code SystemConfigClient#findPublicView}（GET /internal/v1/system-configs/public-view，
 *       契约层已落盘）；解包返回视图同 findCaptchaPolicy 先例。若实现直接注入 SystemConfigClient
 *       或另立网关名，桩目标同步调整。</li>
 *   <li>AMB-2 无行/未知租户语义在 auth 侧以网关桩表达（auth 库无 sys_config）：桩返回缺省视图
 *       (null,false) 与返回 null 两条映射路径均覆盖；真实无行查询由 platform-system
 *       REQ20261117025200TenantProfileFooterIntegrationTest 覆盖（同 captcha AMB-4 口径）。</li>
 *   <li>AMB-3 public-config 公开豁免（双侧 public-paths 一致）：未认证请求形态照抄 captcha-policy
 *       集成测试 policyQuery（仅通道头＋客户端上下文草稿）；若实现未将端点加入 public-paths，
 *       用例 401 失败即为交付缺口信号。</li>
 *   <li>AMB-4 过期登录桩链：画像桩（admin/seed 凭据）＋策略桩（关闭→免验证码）＋by-code 桩
 *       （ENABLED＋expiresAt 过去时）；过期校验位置（租户一致性步骤后，确认记录③）不钉实现顺序，
 *       断言仅钉统一 LOGIN_FAILED 与无会话底账。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req025200-public-secret-01234567",
        "acme.auth.token.hmac-secret=req025200-public-secret-01234567"
})
class REQ20261117025200PublicConfigIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TENANT_X = "req025200-pub-x";
    private static final String TENANT_NONE = "req025200-pub-none";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：LoginGuard / SessionRedis 真实组件（过期登录失败路径）需要。 */
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
    private LoginGuardRedis loginGuard;

    @Autowired
    private SessionRedis sessionRedis;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** public-view / captcha-policy 网关桩（AMB-1）：auth 库无 sys_config，取值一律经该网关表达。 */
    @MockitoBean
    private SystemConfigGateway systemConfigGateway;

    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    /** by-code 契约桩（AMB-4）：登录过期校验与切换目标校验共用 TenantClient.findByCode。 */
    @MockitoBean
    private TenantClient tenantClient;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        // 画像桩返回种子超管画像（user_id=1 对齐 V2 凭据种子，LoginCaptchaPolicy 口径）
        when(authProfileGateway.findByUsername("admin")).thenReturn(
                new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin")));
        // 登录验证码策略关闭（免验证码字段登录，LoginCaptchaPolicy AC-2 口径）
        when(systemConfigGateway.findCaptchaPolicy("default")).thenReturn(new CaptchaPolicyView(false));
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：登录日志 + 防爆破计数 + 会话底账/键（独立容器兜底）
        jdbcTemplate.update("delete from sys_login_log");
        jdbcTemplate.update("delete from auth_session where user_id = 1");
        loginGuard.clearFailures("admin");
        String jti = sessionRedis.currentSessionTokenId(1L);
        if (jti != null) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + jti);
        }
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-9：未认证查询有行租户 → 按行值返回页脚两字段 ----------

    @Test
    void should_returnConfiguredFooter_when_publicConfigQueried() throws Exception {
        when(systemConfigGateway.findPublicView(TENANT_X))
                .thenReturn(new TenantPublicInternalView("© 2026 ACME", true));

        MvcResult result = publicConfigQuery(TENANT_X);

        assertThat(result.getResponse().getStatus()).as("公开端点未认证可读").isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("footerCopyright").asText()).isEqualTo("© 2026 ACME");
        assertThat(body.path("data").path("footerVisible").asBoolean()).isTrue();
    }

    // ---------- AC-9：无行租户 → 缺省视图 copyright=null / visible=false ----------

    @Test
    void should_returnDefaultFooter_when_tenantHasNoPublicConfigRow() throws Exception {
        // 无行语义映射路径一：system public-view 返回缺省视图（null,false）（AMB-2）
        when(systemConfigGateway.findPublicView(TENANT_NONE))
                .thenReturn(new TenantPublicInternalView(null, false));

        MvcResult result = publicConfigQuery(TENANT_NONE);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("footerCopyright").isNull())
                .as("未配置不显示：copyright 缺省 null").isTrue();
        assertThat(body.path("data").path("footerVisible").asBoolean()).isFalse();
    }

    @Test
    void should_returnDefaultFooter_when_publicViewReturnsNull() throws Exception {
        // 无行语义映射路径二：下游解包为 null → auth 端点仍回缺省（AMB-2）
        when(systemConfigGateway.findPublicView(TENANT_NONE)).thenReturn(null);

        MvcResult result = publicConfigQuery(TENANT_NONE);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("footerCopyright").isNull()).isTrue();
        assertThat(body.path("data").path("footerVisible").asBoolean()).isFalse();
    }

    // ---------- 契约 §API：system 服务不可用 → WARN＋缺省返回（页脚非安全面，fail-open） ----------

    @Test
    void should_failOpenWithDefaultFooter_when_systemConfigUnavailable() throws Exception {
        when(systemConfigGateway.findPublicView(TENANT_X))
                .thenThrow(new RuntimeException("req025200-downstream-down"));

        MvcResult result = publicConfigQuery(TENANT_X);

        assertThat(result.getResponse().getStatus())
                .as("fail-open：端点不因下游不可用报错").isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("footerCopyright").isNull()).isTrue();
        assertThat(body.path("data").path("footerVisible").asBoolean()).isFalse();
    }

    // ---------- AC-9 边界（契约 API 节）：空白 tenantCode → 400 参数错误 ----------

    @Test
    void should_rejectBlankTenantCode_when_paramMissingOrBlank() throws Exception {
        MvcResult missingParam = mvc.perform(get("/admin/v1/auth/public-config")
                        .header("X-Acme-Channel", "B")
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        assertThat(missingParam.getResponse().getStatus()).isEqualTo(400);

        MvcResult blankParam = mvc.perform(get("/admin/v1/auth/public-config?tenantCode=")
                        .header("X-Acme-Channel", "B")
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        assertThat(blankParam.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------- AC-5 后半：租户 expiresAt 已过期 → 直接登录统一 LOGIN_FAILED ----------

    @Test
    void should_rejectLoginWithLoginFailed_when_tenantExpired() {
        // by-code 契约返回已过期租户（ENABLED + expiresAt 过去时，确认记录③）
        when(tenantClient.findByCode("default")).thenReturn(new ContractResult<>(true, "0", "ok",
                new TenantInternalView("default", "默认租户", "ENABLED", Instant.now().minusSeconds(3600))));

        JsonNode rejected = login("Admin@123456");

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("过期租户统一 LOGIN_FAILED，不泄露差异（FR-005，AuthErrorCodes 01 段）")
                .isEqualTo(AuthErrorCodes.LOGIN_FAILED);
        assertThat(countSessions()).as("拒绝登录不得产生会话底账").isZero();
        // 过期校验确经 by-code 契约（确认记录③可观测旁证）
        verify(tenantClient).findByCode("default");
    }

    // ---------- 支撑 ----------

    private long countSessions() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_session where user_id = 1", Long.class);
        return count == null ? 0L : count;
    }

    /** B 端登录（策略关闭 → 不携带验证码字段，LoginCaptchaPolicy AMB-5 口径）。 */
    private JsonNode login(String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "default");
        body.put("username", "admin");
        body.put("password", password);
        try {
            MvcResult result = mvc.perform(post("/admin/v1/auth/tokens")
                            .header("X-Acme-Tenant", "default")
                            .header("X-Acme-Channel", "B")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(body))
                            .header("X-Acme-Token", tokenIssuer.issue(
                                    new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("登录请求失败", ex);
        }
    }

    /** 未认证 public-config 查询（无 X-Acme-Token；通道头＋客户端上下文草稿，AMB-3）。 */
    private MvcResult publicConfigQuery(String tenantCode) throws Exception {
        return mvc.perform(get("/admin/v1/auth/public-config?tenantCode=" + tenantCode)
                        .header("X-Acme-Channel", "B")
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
