package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.framework.tenant.support.TenantBypassScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261125-001737 AC-8：{@code acme.tenant.rls.propagation=false} 逃生开关（PG IT，独立 Spring
 * 上下文；无 Docker 自动跳过——跳过不等于验证，判定口径为 {@code Tests run: N, Skipped: 0}）。
 *
 * <p>覆盖：开关关闭时传播层不再写任何 {@code app.tenant_*} 会话变量；ORM 既有租户过滤面与现状一致
 * （绑定 t-1 只查到 t-1 行），避免「关掉传播层连原隔离也关掉」的回归。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.tenant.rls.propagation=false",
        "acme.security.token.hmac-secret=req-1737-off-it-secret-0123456789abcdef"
})
class REQ202611251737PropagationDisabledIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TENANT_A = "req1737-off-a";
    private static final String TENANT_B = "req1737-off-b";
    private static final String USER_A = "req1737-dis-own";
    private static final String USER_B = "req1737-dis-other";

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    private TransactionTemplate transactions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 必须用真正的 JDBC 事务（autoCommit=false）：传播层对 autoCommit 连接本就跳过，
        // 若沿用 FlexTransactionManager，开关 false 的用例会假绿。
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_user where username like 'req1737-dis-%'");
        TenantContext.clear();
    }

    @Test
    void should_writeNoTenantSessionVariables_when_propagationDisabled() {
        TenantContext.bind(new TenantContext(TENANT_A));
        try (TenantBypassScope ignored = TenantBypassScope.open("req1737-ac8-off")) {
            String[] observed = transactions.execute(status -> new String[]{
                    jdbcTemplate.queryForObject("select current_setting('app.tenant_id', true)", String.class),
                    jdbcTemplate.queryForObject("select current_setting('app.tenant_bypass', true)", String.class)
            });
            assertThat(observed[0]).as("开关关闭后不得写 app.tenant_id").isNull();
            assertThat(observed[1]).as("开关关闭后旁路也不得写 app.tenant_bypass").isNull();
        }
    }

    @Test
    void should_keepOrmTenantFiltering_when_propagationDisabled() throws Exception {
        seedUser(USER_A, TENANT_A);
        seedUser(USER_B, TENANT_B);

        JsonNode body = callInTenant(TENANT_A, get("/admin/v1/system/users")
                .param("page", "1").param("size", "50"));

        assertThat(body.path("success").asBoolean()).as("查询应成功：%s", body).isTrue();
        assertThat(usernames(body)).as("传播层关闭不得破坏既有 ORM 租户过滤").contains(USER_A).doesNotContain(USER_B);
    }

    // ---------- 支撑 ----------

    private void seedUser(String username, String tenantId) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, 'ENABLED', false, now(), 'req1737-off-it', now(), 'req1737-off-it', 0, false)
                        """,
                Math.abs(System.identityHashCode(username)) & 0x7FFFFFFFL | 0x700000000L,
                tenantId, username, username);
    }

    private JsonNode callInTenant(String tenant, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", tenant, Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> usernames(JsonNode body) {
        return java.util.stream.StreamSupport.stream(body.path("data").path("records").spliterator(), false)
                .map(record -> record.path("username").asText())
                .toList();
    }
}
