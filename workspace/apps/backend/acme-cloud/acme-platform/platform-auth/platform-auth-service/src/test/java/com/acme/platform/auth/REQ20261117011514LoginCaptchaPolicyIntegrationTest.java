package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.auth.infrastructure.redis.ImageCaptchaRedis;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.CaptchaPolicyView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261117-011514 租户级验证码开关（platform-auth）登录策略服务域集成测试（Testcontainers PG+Redis，
 * 装配口径照抄 {@code REQ20261114120105LoginLogIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-2（策略关闭→不携带验证码字段登录成功且不触碰图形验证码存储）；
 * AC-3（策略开启→缺失/无效验证码按既有错误码拒绝、有效验证码成功）；AC-5（未认证 GET
 * /admin/v1/auth/captcha-policy?tenantCode={code} 返回生效值、无配置行租户 false、空白 tenantCode 400、
 * 下游不可用端点级 fail-closed=true）；AC-6（策略查询下游不可用→登录按「需验证码」fail-closed，
 * 有效验证码仍可登录，不因降级放宽安全）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 {@code SystemConfigGateway} 签名假设：{@code com.acme.platform.auth.infrastructure.client
 *       .SystemConfigGateway#findCaptchaPolicy(String tenantCode)} 返回
 *       {@code com.acme.platform.system.api.CaptchaPolicyView}（AuthProfileGateway/findByUsername
 *       同型先例；CaptchaPolicyView(boolean) 为契约固定签名）。方法名若交付不同，桩名同步调整。</li>
 *   <li>AMB-2 缺失/无效验证码拒绝码取「既有错误码族」{@code AuthError.CAPTCHA_INVALID /
 *       CAPTCHA_EXPIRED}（ImageCaptchaUseCaseTest 钉死语义：值在→INVALID、值缺失→EXPIRED）。
 *       缺失字段允许两种既有实现路径（LoginUseCase 前置拦截→INVALID，或透传 verify→EXPIRED），
 *       断言取族内命中。</li>
 *   <li>AMB-3 图形验证码以 {@code @MockitoBean ImageCaptchaRedis} 桩控制命中（同 LoginLog AMB-2 的
 *       Redis 组件桩先例；verify/exists 方法名钉自 ImageCaptchaUseCaseTest），ImageCaptchaUseCase
 *       保持真实组件——策略门控端到端可观测（AC-2 断言零交互、AC-3 有效路径断言确经校验）。</li>
 *   <li>AMB-4 AC-5 的「无配置行租户返回 false」在 auth 侧以 gateway 桩表达（auth 库无 sys_config，
 *       真实无行语义由 platform-system REQ20261117011514InternalSystemConfigApiTest 覆盖）。</li>
 *   <li>AMB-5 登录请求装配照抄 LoginLog 集成测试：V2 种子超管凭据（user_id=1 / Admin@123456 /
 *       tenant=default）、{@code @MockitoBean AuthProfileGateway} 桩画像、X-Acme-Token 头仅为
 *       MockMvc 过滤链装配需要（端别一致防 CHANNEL_MISMATCH）。</li>
 *   <li>AMB-6 fail-closed 的 WARN 日志（可观测性）不落断言；策略查询端点实时读（确认记录④不引入缓存）
 *       经 AC-2 对 gateway 的单次调用断言旁证。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req011514-captcha-secret-012345678",
        "acme.auth.token.hmac-secret=req011514-captcha-secret-012345678"
})
class REQ20261117011514LoginCaptchaPolicyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String UA = "req011514-ua/1.0";
    private static final String TRACE_ID = "req011514-trace-1";

    private static final String CAPTCHA_ID = "req011514-cid";
    private static final String CAPTCHA_CODE = "A1B2";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：LoginGuard / SessionRedis / ImageCaptchaRedis 真实组件需要。 */
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

    /** 策略查询门（AMB-1 假设签名）：auth 库无 sys_config，策略值一律经该网关桩表达。 */
    @MockitoBean
    private SystemConfigGateway systemConfigGateway;

    /** 图形验证码存储桩（AMB-3）：ImageCaptchaUseCase 保持真实组件。 */
    @MockitoBean
    private ImageCaptchaRedis imageCaptchaRedis;

    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        // 画像桩返回种子超管画像（user_id=1 对齐 V2 凭据种子，LoginLog 口径）
        when(authProfileGateway.findByUsername("admin")).thenReturn(
                new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin")));
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：登录日志全清 + 防爆破计数清理 + 会话/撤销键清理
        jdbcTemplate.update("delete from sys_login_log");
        loginGuard.clearFailures("admin");
        String jti = sessionRedis.currentSessionTokenId(1L);
        if (jti != null) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + jti);
        }
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-2：策略关闭 → 不携带验证码字段登录成功，且跳过图形验证码校验 ----------

    @Test
    void should_loginWithoutCaptchaFields_when_policyDisabled() {
        stubPolicy("default", false);

        JsonNode issued = login("Admin@123456", null, null);

        assertThat(issued.path("code").asText()).as("关闭策略下无验证码登录应成功").isEqualTo("0");
        assertThat(issued.path("data").path("accessToken").asText())
                .as("行为与关闭前有效凭据路径一致：签发令牌").isNotBlank();
        // 策略门控可观测：关闭时完全不触碰图形验证码存储（AMB-3）
        verifyNoInteractions(imageCaptchaRedis);
        // 确认记录④：登录时实时读策略（不引入缓存）——本次登录恰查询一次
        verify(systemConfigGateway).findCaptchaPolicy("default");
    }

    // ---------- AC-3：策略开启 → 有效验证码成功（既有回归不变） ----------

    @Test
    void should_acceptValidCaptcha_when_policyEnabled() {
        stubPolicy("default", true);
        stubValidCaptcha(CAPTCHA_ID, CAPTCHA_CODE);

        JsonNode issued = login("Admin@123456", CAPTCHA_ID, CAPTCHA_CODE);

        assertThat(issued.path("code").asText()).isEqualTo("0");
        assertThat(issued.path("data").path("accessToken").asText()).isNotBlank();
        // 有效路径确经校验（非跳过）：真实 ImageCaptchaUseCase 透传命中
        verify(imageCaptchaRedis).verify(CAPTCHA_ID, CAPTCHA_CODE);
    }

    // ---------- AC-3：策略开启 → 无效验证码按既有错误码拒绝 ----------

    @Test
    void should_rejectWrongCaptchaCode_when_policyEnabled() {
        stubPolicy("default", true);
        // 值仍在（exists=true）但代码错误（verify 未命中，mock 默认 false）→ 既有 CAPTCHA_INVALID
        when(imageCaptchaRedis.exists(CAPTCHA_ID)).thenReturn(true);

        JsonNode rejected = login("Admin@123456", CAPTCHA_ID, "XXXX");

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("既有错误码：值在→INVALID（ImageCaptchaUseCaseTest 同型语义）")
                .isEqualTo(AuthError.CAPTCHA_INVALID.code());
    }

    // ---------- AC-3：策略开启 → 缺失验证码按既有错误码拒绝 ----------

    @Test
    void should_rejectMissingCaptchaFields_when_policyEnabled() {
        stubPolicy("default", true);

        JsonNode rejected = login("Admin@123456", null, null);

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("既有错误码族（前置拦截→INVALID / 透传 verify 值缺失→EXPIRED，AMB-2）")
                .isIn(AuthError.CAPTCHA_INVALID.code(), AuthError.CAPTCHA_EXPIRED.code());
    }

    // ---------- AC-5：未认证查询 captcha-policy 返回生效值 ----------

    @Test
    void should_returnEffectivePolicy_when_unauthenticatedQueryByTenantCode() throws Exception {
        stubPolicy("default", true);

        MvcResult result = policyQuery("/admin/v1/auth/captcha-policy?tenantCode=default");

        assertThat(result.getResponse().getStatus()).as("公开端点未认证可读").isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("captchaEnabled").asBoolean()).isTrue();
    }

    @Test
    void should_returnDisabledPolicy_when_tenantHasNoRow() throws Exception {
        // AMB-4：auth 侧无行语义以 gateway 桩表达；platform-system 侧真实无行查询另行覆盖
        stubPolicy("req011514-no-row", false);

        MvcResult result = policyQuery("/admin/v1/auth/captcha-policy?tenantCode=req011514-no-row");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("captchaEnabled").asBoolean()).isFalse();
    }

    // ---------- AC-5 边界（契约 API 节）：空白 tenantCode → 400 参数错误 ----------

    @Test
    void should_rejectBlankTenantCode_when_paramMissingOrBlank() throws Exception {
        MvcResult missingParam = mvc.perform(get("/admin/v1/auth/captcha-policy")
                        .header("X-Acme-Channel", "B")
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        assertThat(missingParam.getResponse().getStatus()).isEqualTo(400);

        MvcResult blankParam = mvc.perform(get("/admin/v1/auth/captcha-policy?tenantCode=")
                        .header("X-Acme-Channel", "B")
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        assertThat(blankParam.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------- AC-5 边界（契约 API 节）：策略查询下游不可用 → 端点级 fail-closed true ----------

    @Test
    void should_failClosedReturnTrue_when_policyDownstreamUnavailable() throws Exception {
        when(systemConfigGateway.findCaptchaPolicy("default"))
                .thenThrow(new RuntimeException("req011514-downstream-down"));

        MvcResult result = policyQuery("/admin/v1/auth/captcha-policy?tenantCode=default");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("captchaEnabled").asBoolean())
                .as("下游不可用→fail-closed 返回 true＋WARN（WARN 不断言，AMB-6）").isTrue();
    }

    // ---------- AC-6：策略查询下游不可用 → 登录 fail-closed 要求验证码 ----------

    @Test
    void should_failClosedRejectMissingCaptcha_when_policyLookupFails() {
        when(systemConfigGateway.findCaptchaPolicy("default"))
                .thenThrow(new RuntimeException("req011514-downstream-down"));

        JsonNode rejected = login("Admin@123456", null, null);

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("fail-closed 按「需验证码」拒绝缺失验证码的请求（错误码族 AMB-2）")
                .isIn(AuthError.CAPTCHA_INVALID.code(), AuthError.CAPTCHA_EXPIRED.code());
    }

    @Test
    void should_acceptValidCaptcha_when_policyLookupFails() {
        // fail-closed ≠ 硬拒：降级视为「需验证码」，携带有效验证码仍可登录
        when(systemConfigGateway.findCaptchaPolicy("default"))
                .thenThrow(new RuntimeException("req011514-downstream-down"));
        stubValidCaptcha(CAPTCHA_ID, CAPTCHA_CODE);

        JsonNode issued = login("Admin@123456", CAPTCHA_ID, CAPTCHA_CODE);

        assertThat(issued.path("code").asText()).isEqualTo("0");
        assertThat(issued.path("data").path("accessToken").asText()).isNotBlank();
    }

    // ---------- 支撑 ----------

    private void stubPolicy(String tenantCode, boolean enabled) {
        when(systemConfigGateway.findCaptchaPolicy(tenantCode)).thenReturn(new CaptchaPolicyView(enabled));
    }

    private void stubValidCaptcha(String captchaId, String captchaCode) {
        when(imageCaptchaRedis.verify(captchaId, captchaCode)).thenReturn(true);
        when(imageCaptchaRedis.exists(captchaId)).thenReturn(true);
    }

    /** B 端登录（captchaId/captchaCode 传 null 时省略字段，模拟「不携带验证码字段」）。 */
    private JsonNode login(String password, String captchaId, String captchaCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "default");
        body.put("username", "admin");
        body.put("password", password);
        if (captchaId != null) {
            body.put("captchaId", captchaId);
        }
        if (captchaCode != null) {
            body.put("captchaCode", captchaCode);
        }
        return call(post("/admin/v1/auth/tokens")
                .header("X-Acme-Tenant", "default")
                .header("User-Agent", UA)
                .header("X-Acme-Trace-Id", TRACE_ID)
                .header("X-Trace-Id", TRACE_ID)
                .header("X-Acme-Channel", "B")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** 未认证策略查询（无 X-Acme-Token；仅网关转发形态的通道头＋客户端上下文草稿，AMB-5）。 */
    private MvcResult policyQuery(String uri) {
        try {
            return mvc.perform(get(uri)
                            .header("X-Acme-Channel", "B")
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException("策略查询请求失败：" + uri, ex);
        }
    }

    /** token(...) 仅为 MockMvc 过滤链装配需要（LoginLog AMB-5 口径）。 */
    private JsonNode call(MockHttpServletRequestBuilder request) {
        try {
            MvcResult result = mvc.perform(request
                            .header("X-Acme-Token", tokenIssuer.issue(
                                    new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("请求失败：" + request, ex);
        }
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
