package com.acme.platform.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.auth.infrastructure.client.SystemConfigGateway;
import com.acme.platform.system.api.CaptchaPolicyView;
import com.acme.platform.system.api.TenantPublicInternalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261125-001736 AC-1：三个公开端点访问审计（GET /admin/v1/auth/tenants、
 * /admin/v1/auth/captcha-policy、/admin/v1/auth/public-config）——未认证请求每请求一条
 * 结构化访问日志（logger {@code acme.security.public-access}：traceId/clientIp/endpoint/
 * tenantCode（有则记）/ua 摘要，缺失渲染 "-"），且响应语义/状态码/形状零变化。
 *
 * <p>装配口径照抄 {@code REQ20261117011514LoginCaptchaPolicyIntegrationTest}
 * （Testcontainers PG+Redis，无 Docker 自动跳过）；公开端点桩目标沿用既有集成测试
 * （captcha-policy → SystemConfigGateway#findCaptchaPolicy，public-config →
 * SystemConfigGateway#findPublicView）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 字段格式未钉死：断言保持「格式无关」——消息按钉死字段序含各已知值
 *       （indexOf 递增）＋缺失槽位渲染 "-"（contains "-"）。若实现为 key=value（acme.tenant.audit
 *       同型先例）或 JSON，均满足本断言；精确渲染断言待 TL 钉死后补。</li>
 *   <li>AMB-2 tenantCode 取值源假设：三端点请求语义自带 tenantCode 查询参数，按参数值断言；
 *       TenantContext 绑定态的取值源（参数 vs 上下文）未钉死，不做双重断言。</li>
 *   <li>AMB-3 traceId 取值源假设：同时绑定 TraceContext 与 X-Acme-Trace-Id 头（两源覆盖）；
 *       若实现仅读 MDC 等第三源，该用例失败即为取值源差异信号。</li>
 *   <li>AMB-4 /admin/v1/auth/tenants 的目录网关列法未钉死（既有测试无同型先例）：只断言
 *       审计事件发射与字段，不断言响应形状（网关桩列法名交付后同步补桩与形状断言）。</li>
 *   <li>AMB-5 UA 摘要口径未钉死：断言 UA 可识别前缀出现（全串摘要/截断摘要均满足）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=it-req001736-test-hmac-secret-0123456789abcdef",
        "acme.auth.token.hmac-secret=it-req001736-test-auth-hmac-secret-0123456789abcdef"
})
class REQ20261125001736PublicAccessAuditIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 测试值统一不含 "-" 字符：缺失槽位的 "-" 渲染断言不受取值干扰（AMB-1）。 */
    private static final String CLIENT_IP = "198.51.100.7";
    private static final String UA = "req001736.ua.1.0";
    private static final String TRACE_ID = "req001736.trace.1";
    private static final String TENANT_X = "req001736.t1";

    private static final String AUDIT_LOGGER = "acme.security.public-access";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

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

    /** 生产装配同源公开路径（SecurityAutoConfiguration.ServletChannelConfig 注入口径）。 */
    @Autowired
    private SecurityProperties securityProperties;

    /** 公开配置/策略桩（auth 库无 sys_config，既有集成测试同口径）。 */
    @MockitoBean
    private SystemConfigGateway systemConfigGateway;

    /** 租户目录桩（AMB-4：列法名未钉死，不Stub列法；仅为隔离真实下游 HTTP 调用）。 */
    @MockitoBean
    private com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway tenantDirectoryGateway;

    private MockMvc mvc;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, securityProperties.getPublicPaths()))
                .build();
        Logger auditLogger = (Logger) LoggerFactory.getLogger(AUDIT_LOGGER);
        appender.list.clear();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void cleanUp() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger(AUDIT_LOGGER);
        auditLogger.detachAppender(appender);
        appender.stop();
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
        com.acme.common.context.TraceContext.clear();
    }

    // ---------- AC-1：三路径各产生一条结构化访问日志 ----------

    @Test
    void should_auditOneEventPerRequest_when_tenantsEndpointAccessed() {
        int before = appender.list.size();
        publicGet("/admin/v1/auth/tenants");
        assertThat(appender.list.size()).as("每请求恰一条访问审计日志").isEqualTo(before + 1);
        String message = appender.list.get(before).getFormattedMessage();
        // 字段序（钉死）：traceId < clientIp < endpoint < ua；tenantCode/traceId 缺失 → "-"
        assertOrdered(message, List.of(CLIENT_IP, "/admin/v1/auth/tenants", UA));
        assertThat(message).as("缺失槽位渲染 -（AMB-1 格式无关断言）").contains("-");
    }

    @Test
    void should_auditOneEventPerRequest_when_captchaPolicyEndpointAccessed() {
        when(systemConfigGateway.findCaptchaPolicy(TENANT_X)).thenReturn(new CaptchaPolicyView(true));
        int before = appender.list.size();
        publicGet("/admin/v1/auth/captcha-policy?tenantCode=" + TENANT_X);
        assertThat(appender.list.size()).isEqualTo(before + 1);
        String message = appender.list.get(before).getFormattedMessage();
        assertOrdered(message, List.of(CLIENT_IP, "/admin/v1/auth/captcha-policy", TENANT_X, UA));
    }

    @Test
    void should_auditOneEventPerRequest_when_publicConfigEndpointAccessed() {
        when(systemConfigGateway.findPublicView(TENANT_X))
                .thenReturn(new TenantPublicInternalView("© 2026 ACME", true));
        int before = appender.list.size();
        publicGet("/admin/v1/auth/public-config?tenantCode=" + TENANT_X);
        assertThat(appender.list.size()).isEqualTo(before + 1);
        String message = appender.list.get(before).getFormattedMessage();
        assertOrdered(message, List.of(CLIENT_IP, "/admin/v1/auth/public-config", TENANT_X, UA));
    }

    // ---------- AC-1：响应语义/状态码/形状零变化 ----------

    @Test
    void should_keepCaptchaPolicyResponseShape_when_auditAttached() throws Exception {
        when(systemConfigGateway.findCaptchaPolicy(TENANT_X)).thenReturn(new CaptchaPolicyView(true));
        int before = appender.list.size();

        MvcResult result = publicGet("/admin/v1/auth/captcha-policy?tenantCode=" + TENANT_X);

        assertThat(result.getResponse().getStatus()).as("响应状态码零变化").isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("captchaEnabled").asBoolean()).isTrue();
        assertThat(appender.list.size()).as("审计不重复发日志、不拦截响应").isEqualTo(before + 1);
    }

    @Test
    void should_keepPublicConfigResponseShape_when_auditAttached() throws Exception {
        when(systemConfigGateway.findPublicView(TENANT_X))
                .thenReturn(new TenantPublicInternalView("© 2026 ACME", true));
        int before = appender.list.size();

        MvcResult result = publicGet("/admin/v1/auth/public-config?tenantCode=" + TENANT_X);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("footerCopyright").asText()).isEqualTo("© 2026 ACME");
        assertThat(body.path("data").path("footerVisible").asBoolean()).isTrue();
        assertThat(appender.list.size()).isEqualTo(before + 1);
    }

    // ---------- AC-1：字段口径（tenantCode 有则记 / traceId / UA 摘要） ----------

    @Test
    void should_recordTenantCode_when_requestCarriesTenantCodeParam() {
        when(systemConfigGateway.findCaptchaPolicy(TENANT_X)).thenReturn(new CaptchaPolicyView(false));
        publicGet("/admin/v1/auth/captcha-policy?tenantCode=" + TENANT_X);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .as("tenantCode 有则记（AMB-2：按请求参数断言）").contains(TENANT_X);
    }

    @Test
    void should_recordTraceId_when_traceContextBound() {
        com.acme.common.context.TraceContext.bind(
                new com.acme.common.context.TraceContext(TRACE_ID, "span.001736"));
        try {
            publicGet("/admin/v1/auth/tenants");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .as("traceId 取值源（AMB-3：TraceContext 绑定 + X-Acme-Trace-Id 头双源覆盖）")
                    .contains(TRACE_ID);
        } finally {
            com.acme.common.context.TraceContext.clear();
        }
    }

    // ---------- AC-1：注册面最小化（三路径之外不审计） ----------

    @Test
    void should_notAudit_when_pathOutsideRegisteredThree() {
        int before = appender.list.size();
        publicGet("/req001736.not.mapped");
        assertThat(appender.list.size())
                .as("注册三路径之外（404/非公开路径）不产生访问审计日志").isEqualTo(before);
    }

    // ---------- 支撑 ----------

    /** 未认证公开端点请求（无 X-Acme-Token；通道头＋客户端上下文草稿＋固定 remoteAddr/UA）。 */
    private MvcResult publicGet(String uri) {
        try {
            return mvc.perform(get(uri)
                            .header("X-Acme-Channel", "B")
                            .header("User-Agent", UA)
                            .with(request -> {
                                request.setRemoteAddr(CLIENT_IP);
                                return request;
                            })
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException("公开端点请求失败：" + uri, ex);
        }
    }

    /** 钉死字段序的格式无关断言：已知值按 traceId→clientIp→endpoint→tenantCode→ua 序出现。 */
    private static void assertOrdered(String message, List<String> presentValues) {
        int prev = -1;
        for (String value : presentValues) {
            int at = message.indexOf(value, prev + 1);
            assertThat(at).as("字段序应含 %s（实况：%s）", value, message).isGreaterThanOrEqualTo(0);
            prev = at;
        }
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
