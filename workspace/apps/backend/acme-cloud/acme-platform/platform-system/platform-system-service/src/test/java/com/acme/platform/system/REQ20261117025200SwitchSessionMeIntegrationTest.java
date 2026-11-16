package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.SystemErrorCodes;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261117-025200 切换会话 /me 身份跨租户解析（platform-system，GET /admin/v1/system/me）
 * 服务域集成测试（Testcontainers PG，装配口径照抄 REQ20261117025200TenantProfileFooterIntegrationTest；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖 AC-6 后半句（确认记录④）：超管切换到目标租户后，持新令牌调 /me——user 行返回
 * （PK 全局直读，行归属源租户 default）、permissions 非空（decisionService 内置角色回退 +
 * 绑定旁路既有链路）、菜单树非空（本租户角色未命中 → 内置角色跨租户回退 + role_menu 绑定旁路，
 * 旁路 accessPoint 登记 switch-session-user-identity-lookup / built-in-role-cross-tenant-lookup /
 * built-in-role-menu-cross-tenant-lookup）；常态 default 租户会话回归不变（本租户命中路径）。</p>
 *
 * <p>口径说明：非超管切换已被 403 挡住（REQ20261117025200TenantSwitchIntegrationTest AC-7），
 * 不存在「非超管切换会话」可抵达 /me 的状态，无需重复覆盖；切换互踢单会话语义由 auth 侧覆盖。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req025200-me-secret-012345678"
})
class REQ20261117025200SwitchSessionMeIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 切换目标租户（无 sys_tenant 种子行：切换仅校验目录行存在，会话上下文租户值来自令牌 claims）。 */
    private static final String SWITCHED_TENANT = "req025200-switched-t2";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
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
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, java.util.List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-6 后半句：切换超管会话调 /me → user 行返回、permissions 非空、菜单树非空 ----------

    @Test
    void should_returnUserPermissionsAndMenus_when_switchedSuperAdminSessionCallsMe() throws Exception {
        // 切换会话上下文（tenant claim = 目标租户，user 行归属 default）：/me 三要素齐备
        JsonNode me = callMe("1", SWITCHED_TENANT, Set.of("super_admin"));

        assertThat(me.path("code").asText()).as("切换会话 /me 可达").isEqualTo("0");
        JsonNode data = me.path("data");
        // user 行（PK 全局直读：switch-session-user-identity-lookup 旁路，行归属 default 租户）
        assertThat(data.path("user").path("username").asText())
                .as("切换会话身份跨租户解析（AC-6 后半句，原缺陷 USER_NOT_FOUND）").isEqualTo("admin");
        assertThat(data.path("roles").size()).isEqualTo(1);
        assertThat(data.path("roles").get(0).asText()).isEqualTo("super_admin");
        // permissions（decisionService 内置角色回退 + 绑定旁路既有链路，确认无需改动）
        assertThat(data.path("permissions").size())
                .as("内置超管在目标租户上下文持全量权限点绑定").isPositive();
        // 菜单树（本租户角色未命中 → 内置角色跨租户回退 + role_menu 绑定旁路，确认记录④）
        JsonNode menus = data.path("menus");
        assertThat(menus.size()).as("切换会话菜单树非空").isPositive();
        assertThat(StreamSupport.stream(menus.spliterator(), false)
                .anyMatch(node -> node.path("children").size() > 0))
                .as("父链自动补全后目录含子节点（内置超管全量菜单绑定）").isTrue();
    }

    @Test
    void should_keepLocalTenantMePath_when_normalDefaultSessionCallsMe() throws Exception {
        // 回归：常态 default 租户超管会话（本租户角色命中路径）不变——菜单/权限照常返回
        JsonNode me = callMe("1", "default", Set.of("super_admin"));

        assertThat(me.path("code").asText()).isEqualTo("0");
        JsonNode data = me.path("data");
        assertThat(data.path("user").path("username").asText()).isEqualTo("admin");
        assertThat(data.path("permissions").size()).isPositive();
        assertThat(data.path("menus").size()).isPositive();
    }

    @Test
    void should_rejectUserNotFound_when_switchedSessionUserIdMissingGlobally() throws Exception {
        // PK 全局直读仍无行（脏数据）：显式 USER_NOT_FOUND（404 错误信封，同画像查询口径，不静默放行）
        MvcResult result = performMe("925404", SWITCHED_TENANT, Set.of("super_admin"));
        assertThat(result.getResponse().getStatus())
                .as("USER_NOT_FOUND 404 错误信封（GlobalExceptionTranslator 映射）").isEqualTo(404);
        JsonNode body = bodyOf(result);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(SystemErrorCodes.USER_NOT_FOUND);
    }

    // ---------- 支撑 ----------

    private JsonNode callMe(String userId, String tenantId, Set<String> roles) throws Exception {
        MvcResult result = performMe(userId, tenantId, roles);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return bodyOf(result);
    }

    private MvcResult performMe(String userId, String tenantId, Set<String> roles) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/v1/system/me")
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(
                        new AuthenticatedSubject(userId, tenantId, Channel.B, roles)))
                .contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(request).andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
