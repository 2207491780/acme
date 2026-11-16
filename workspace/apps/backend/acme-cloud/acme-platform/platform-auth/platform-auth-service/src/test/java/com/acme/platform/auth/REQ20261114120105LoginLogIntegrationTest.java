package com.acme.platform.auth;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.application.ImageCaptchaUseCase;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.redis.LoginGuardRedis;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.system.api.AuthProfileView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261114-120105 AC-1：登录日志落库（B/C 双端，成功/失败）服务域集成测试（Testcontainers PG+Redis，
 * 照抄 {@code REQ20261114120104DeptApiIntegrationTest} / {@code AuthSeedIntegrationTest} 装配口径；
 * 无 Docker 自动跳过）。「日志写失败不影响登录主路径」独立容器用例见
 * {@code REQ20261114120105LoginLogWriteFailureIntegrationTest}（drop 表注入，避免污染本类共享库）。
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 登录请求体字段名（backend 交付钉死）：username/password/captchaId/captchaCode/tenantCode
 *       （既有 AdminLoginRequest 契约）；header X-Acme-Tenant 双发保留。</li>
 *   <li>AMB-2 图形验证码前置：B 端以 {@code @MockitoBean ImageCaptchaUseCase} 桩放行；C 端以
 *       {@code @MockitoBean CaptchaRedis} 桩验证码命中（首登自动注册走真库）。画像查询经
 *       {@code @MockitoBean AuthProfileGateway} 桩（platform-system 不在本测试上下文，审计域测试同口径）。</li>
 *   <li>AMB-3 result/user_type wire 字面量未指明：result 仅断言非空；user_type 断言 B/C
 *       （Channel wire 先例）。B 端 username=admin；C 端无用户名，断言 username=phone（AMB）。</li>
 *   <li>AMB-4 trace_id 来源 header 名未指明：X-Acme-Trace-Id / X-Trace-Id 双发同值，断言列非空。</li>
 *   <li>AMB-5 日志为异步落库（契约「事件异步落库」）：断言一律轮询（5s 上限），同步实现亦兼容。</li>
 *   <li>凭证前置：V2 种子超管凭据（user_id=1 / Admin@123456 / tenant=default，AuthSeedIntegrationTest 口径）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req120105-loginlog-secret-012345678",
        "acme.auth.token.hmac-secret=req120105-loginlog-secret-012345678"
})
class REQ20261114120105LoginLogIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String UA = "req120105-ua/1.0";
    private static final String TRACE_ID = "req120105-trace-1";
    private static final String PHONE = "13800138000";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：LoginGuard / SessionRedis 真实组件（防爆破计数 + 会话索引）需要。 */
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

    @MockitoBean
    private ImageCaptchaUseCase imageCaptchaUseCase;

    @MockitoBean
    private com.acme.platform.auth.infrastructure.redis.CaptchaRedis captchaRedis;

    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        // 验证码前置桩放行（AMB-2）；画像桩返回种子超管画像（user_id=1 对齐 V2 凭据种子）
        doNothing().when(imageCaptchaUseCase).verify(anyString(), anyString());
        when(authProfileGateway.findByUsername("admin")).thenReturn(
                new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin")));
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：登录日志全清 + C 端注册账户清理（表名按仓储惯例推定，AMB-6 守护） +
        // 防爆破计数清理 + 已知会话/撤销键清理（C 端会话键口径实现不可知，残留随 TTL 回收——AMB-6）
        jdbcTemplate.update("delete from sys_login_log");
        try {
            jdbcTemplate.update("delete from auth_consumer_account where phone = ?", PHONE);
        } catch (Exception ignored) {
            // AMB-6：表/列名以交付迁移为准；teardown 静默容忍，避免遮蔽用例结果
        }
        loginGuard.clearFailures("admin");
        String jti = sessionRedis.currentSessionTokenId(1L);
        if (jti != null) {
            redisTemplate.delete(SessionRedis.REVOKE_KEY_PREFIX + jti);
        }
        redisTemplate.delete(SessionRedis.SESSION_INDEX_PREFIX + "1");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：B 端成功登录 → 一条日志（ip/ua/result/trace_id，user_type=B） ----------

    @Test
    void should_writeAdminSuccessLoginLog_when_adminLoginSucceeds() {
        login("Admin@123456", "B");

        awaitLogRow("username = 'admin' and fail_reason is null");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from sys_login_log where username='admin' and fail_reason is null");
        assertThat(row.get("user_type")).as("B 端登录日志 user_type=B（AMB-3）").isEqualTo("B");
        assertThat(String.valueOf(row.get("result"))).as("成功日志 result 非空（wire 字面量 AMB-3）").isNotBlank();
        assertThat(String.valueOf(row.get("ip"))).as("成功日志含 ip").isNotBlank();
        assertThat(row.get("user_agent")).as("成功日志含请求 UA").isEqualTo(UA);
        assertThat(String.valueOf(row.get("trace_id"))).as("成功日志含 trace_id（AMB-4）").isNotBlank();
        assertThat(row.get("created_at")).as("成功日志含落库时间").isNotNull();
    }

    // ---------- AC-1：B 端失败登录 → 一条失败日志（fail_reason 非空） ----------

    @Test
    void should_writeAdminFailedLoginLogWithReason_when_adminLoginWrongPassword() {
        JsonNode rejected = login("Wrong@12345", "B");

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("统一失败为业务码≠0（LOGIN_FAILED wire 值 AMB-1，勿断言 4xx）").isNotEqualTo("0");

        awaitLogRow("username = 'admin' and fail_reason is not null");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from sys_login_log where username='admin' and fail_reason is not null");
        assertThat(String.valueOf(row.get("result"))).as("失败日志 result 非空").isNotBlank();
        assertThat(row.get("user_type")).isEqualTo("B");
        assertThat(String.valueOf(row.get("ip"))).isNotBlank();
    }

    // ---------- AC-1：C 端成功登录 → 一条日志（user_type=C，username=phone） ----------

    @Test
    void should_writeConsumerLoginLog_when_consumerLoginSucceeds() {
        when(captchaRedis.verify(PHONE, "123456")).thenReturn(true);

        JsonNode issued = consumerLogin();

        assertThat(issued.path("code").asText()).as("C 端首登自动注册应成功").isEqualTo("0");
        assertThat(issued.path("data").path("accessToken").asText()).isNotBlank();

        awaitLogRow("username = '" + PHONE + "' and fail_reason is null");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from sys_login_log where username = ?", PHONE);
        assertThat(row.get("user_type")).as("C 端登录日志 user_type=C（AMB-3）").isEqualTo("C");
        assertThat(String.valueOf(row.get("result"))).isNotBlank();
        assertThat(String.valueOf(row.get("ip"))).isNotBlank();
        assertThat(row.get("user_agent")).isEqualTo(UA);
        assertThat(String.valueOf(row.get("trace_id"))).isNotBlank();
    }

    // ---------- 支撑 ----------

    private JsonNode login(String password, String channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "default");
        body.put("username", "admin");
        body.put("password", password);
        body.put("captchaId", "req120105-captcha-id");
        body.put("captchaCode", "a1b2");
        return call(post("/admin/v1/auth/tokens")
                .header("X-Acme-Tenant", "default")
                .header("User-Agent", UA)
                .header("X-Acme-Trace-Id", TRACE_ID)
                .header("X-Trace-Id", TRACE_ID)
                .header("X-Acme-Channel", channel)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private JsonNode consumerLogin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", PHONE);
        body.put("captcha", "123456");
        return call(post("/app/v1/auth/tokens")
                .header("User-Agent", UA)
                .header("X-Acme-Trace-Id", TRACE_ID)
                .header("X-Trace-Id", TRACE_ID)
                .header("X-Acme-Channel", "C")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)), Channel.C);
    }

    /** token(...) 仅为 MockMvc 过滤链装配需要：端别须与请求 X-Acme-Channel 声明一致（否则 CHANNEL_MISMATCH）。 */
    private String token(String userId, String tenant, Set<String> roles, Channel channel) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, tenant, channel, roles));
    }

    private JsonNode call(MockHttpServletRequestBuilder request) {
        return call(request, Channel.B);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Channel channel) {
        try {
            MvcResult result = mvc.perform(request
                            .header("X-Acme-Token", token("1", "default", Set.of("super_admin"), channel))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("请求失败：" + request, ex);
        }
    }

    /** 异步落库轮询（AMB-5）：5s 上限，同步实现立即通过。 */
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
        org.assertj.core.api.Assertions.fail("等待条件超时（%s）", timeout);
    }
}
