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
import com.acme.platform.auth.infrastructure.client.TenantDirectoryGateway;
import com.acme.platform.system.api.AuthProfileView;
import com.acme.platform.system.api.TenantInternalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261114-120105 AC-1（尾部子句）：日志写失败不影响登录主路径（独立容器，避免 drop 表污染
 * {@code REQ20261114120105LoginLogIntegrationTest} 的共享库）。装配照抄 {@code AuthSeedIntegrationTest}。
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>写失败注入方式契约未指明（禁读实现，无法按类注入）：以 drop 掉 {@code sys_login_log}
 *       表达「日志存储不可用」——契约语义下登录必须仍返回成功（code=0 + 令牌签发）。
 *       若记录点为同步且未捕获异常 → 本用例失败 = 实现违背 AC-1；若异步落库失败被吞 → 亦符合。</li>
 *   <li>验证码前置/画像查询桩口径同 {@code REQ20261114120105LoginLogIntegrationTest}（AMB-2）。</li>
 *   <li>drop 发生在上下文启动之后（ddl-auto=validate 启动校验不受影响）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformAuthApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req120105-logfail-secret-0123456789",
        "acme.auth.token.hmac-secret=req120105-logfail-secret-0123456789"
})
class REQ20261114120105LoginLogWriteFailureIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ImageCaptchaUseCase imageCaptchaUseCase;

    @MockitoBean
    private AuthProfileGateway authProfileGateway;

    /** 租户目录桩（REQ-20261117-025200 确认记录③）：登录第 4.5 步 by-code 实查，default 租户启用未过期。 */
    @MockitoBean
    private TenantDirectoryGateway tenantDirectoryGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        doNothing().when(imageCaptchaUseCase).verify(anyString(), anyString());
        when(authProfileGateway.findByUsername("default", "admin")).thenReturn(
                new AuthProfileView("1", "admin", "超级管理员", "default", "ENABLED", Set.of("super_admin")));
        // REQ-20261117-025200：登录租户有效期校验数据源——default 租户启用且未过期
        when(tenantDirectoryGateway.findByCode("default")).thenReturn(
                new TenantInternalView("default", "默认租户", "ENABLED", null));
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：日志写失败 → 登录主路径不受影响 ----------

    @Test
    void should_completeLogin_when_loginLogWriteFails() {
        // 日志存储不可用（drop 契约 V6 表）
        jdbcTemplate.execute("drop table if exists sys_login_log");

        JsonNode issued = call(post("/admin/v1/auth/tokens")
                .header("X-Acme-Tenant", "default")
                .header("X-Acme-Channel", "B")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(loginBody("Admin@123456"))));

        assertThat(issued.path("success").asBoolean())
                .as("日志写失败不得影响登录主路径（AC-1 尾部子句）").isTrue();
        assertThat(issued.path("code").asText()).isEqualTo("0");
        assertThat(issued.path("data").path("accessToken").asText()).as("令牌仍应签发").isNotBlank();
    }

    // ---------- 支撑 ----------

    private static Map<String, Object> loginBody(String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "default");
        body.put("username", "admin");
        body.put("password", password);
        body.put("captchaId", "req120105-captcha-id");
        body.put("captchaCode", "a1b2");
        return body;
    }

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
}
