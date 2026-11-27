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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261125-001737 AC-1 / AC-6（API 面）：连接传播层会话变量的 PG 真库语义 + 平台目录表管理 API
 * 全量可见（Testcontainers PG，装配口径照 {@code REQ20261114120104TenantIsolationIntegrationTest}；
 * 无 Docker 自动跳过——判定口径：日志须见 {@code Tests run: N, Skipped: 0} 才算验证）。
 *
 * <p>AC-1：事务内绑定租户 → 首条 SQL 可读到 {@code app.tenant_id=t-1}（等价于「首条语句前已 SET LOCAL」）；
 * 未绑定 → 哨兵；旁路激活 → {@code app.tenant_bypass=on}；SET LOCAL 只活到事务结束，无跨事务残留。</p>
 *
 * <p>本类读取会话变量走 Spring 托管 DataSource（容器超管），仅用于观察传播层设置的变量值，
 * 不作为 RLS 隔离铁证；RLS 铁证见 {@code REQ202611251737RlsNonSuperuserIntegrationTest}
 * （真正以迁移创建的非超管应用角色 login）。</p>
 */
class REQ202611251737PropagationIntegrationTest extends AbstractSystemIntegrationTest {

    /** 契约背景钉死的 ORM 哨兵（确认记录②：fail-closed 与之同口径）。 */
    private static final String SENTINEL = "__acme_tenant_unbound__";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TENANT_FIXTURE_PREFIX = "req1737ctl-";

    private static final Map<String, String> CATALOG_PATHS = new LinkedHashMap<>();

    static {
        CATALOG_PATHS.put("/admin/v1/system/tenants", "sys_tenant");
        CATALOG_PATHS.put("/admin/v1/system/permissions", "sys_permission");
        CATALOG_PATHS.put("/admin/v1/system/menus", "sys_menu");
        CATALOG_PATHS.put("/admin/v1/system/tenant-packages", "sys_tenant_package");
        CATALOG_PATHS.put("/admin/v1/system/tenant-templates", "sys_tenant_init_template");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private javax.sql.DataSource dataSource;

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
        // 传播层按钉死事实只在 autoCommit=false 的 JDBC 事务内写 SET LOCAL；
        // FlexTransactionManager 在切片里保持 autoCommit=true，故用 JDBC 事务管理器承载 AC-1 语义。
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", TENANT_FIXTURE_PREFIX + "%");
        TenantContext.clear();
    }

    @Test
    void should_setTenantSessionVariableBeforeFirstSql_when_transactionBoundToTenant() {
        TenantContext.bind(new TenantContext("t-1"));

        String observed = transactions.execute(status ->
                jdbcTemplate.queryForObject("select current_setting('app.tenant_id', true)", String.class));

        assertThat(observed).as("首条业务 SQL 即可读到绑定租户 = 变量在首条 SQL 前已 SET")
                .isEqualTo("t-1");
    }

    @Test
    void should_setSentinelSessionVariable_when_tenantContextUnbound() {
        TenantContext.clear();

        String observed = transactions.execute(status ->
                jdbcTemplate.queryForObject("select current_setting('app.tenant_id', true)", String.class));

        assertThat(observed).as("未绑定必须落哨兵（不匹配任何真实租户），不得落到默认租户")
                .isEqualTo(SENTINEL)
                .isNotEqualTo("default");
    }

    @Test
    void should_setBypassSessionVariable_when_bypassScopeActive() {
        TenantContext.bind(new TenantContext("t-1"));

        try (TenantBypassScope ignored = TenantBypassScope.open("req1737-ac1-bypass")) {
            String observed = transactions.execute(status -> jdbcTemplate.queryForObject(
                    "select current_setting('app.tenant_bypass', true)", String.class));
            assertThat(observed).as("旁路激活时会话变量必须置 on").isEqualTo("on");
        }
    }

    @Test
    void should_notLeakSessionVariablesAcrossTransactions_when_transactionEnds() {
        TenantContext.bind(new TenantContext("t-1"));
        String inFirstTransaction = transactions.execute(status ->
                jdbcTemplate.queryForObject("select current_setting('app.tenant_id', true)", String.class));
        assertThat(inFirstTransaction).isEqualTo("t-1");

        // SET LOCAL 生命周期=事务：事务外不得残留上一事务租户。
        // PG 对未显式设置的带点自定义 GUC 返回空串（而非 null），故按「无值」断言。
        String afterCommit = jdbcTemplate.queryForObject(
                "select current_setting('app.tenant_id', true)", String.class);
        assertThat(afterCommit == null || afterCommit.isEmpty())
                .as("事务提交后 SET LOCAL 自动失效，不得残留 t-1").isTrue();

        // 新事务未绑定 → 哨兵，而非上一个事务的 t-1（无跨事务残留）
        TenantContext.clear();
        String inNextTransaction = transactions.execute(status ->
                jdbcTemplate.queryForObject("select current_setting('app.tenant_id', true)", String.class));
        assertThat(inNextTransaction).as("新事务不得继承上一事务租户").isEqualTo(SENTINEL);
    }

    // ---------- AC-6（API 面）：目录表管理 API 全量可见 ----------

    @Test
    void should_exposeFullCatalogViaAdminApis_when_tenantContextBound() throws Exception {
        Map<String, Long> dbCounts = new LinkedHashMap<>();
        for (String table : CATALOG_PATHS.values()) {
            dbCounts.put(table, jdbcTemplate.queryForObject("select count(*) from " + table, Long.class));
        }
        String tenantA = createTenant(TENANT_FIXTURE_PREFIX + "a");
        String tenantB = createTenant(TENANT_FIXTURE_PREFIX + "b");
        dbCounts.put("sys_tenant", dbCounts.get("sys_tenant") + 2);

        JsonNode tenants = callInTenant("t-1", get("/admin/v1/system/tenants")
                .param("page", "1").param("size", "200"));
        assertThat(tenants.path("success").asBoolean()).as("租户列表应成功：%s", tenants).isTrue();
        assertThat(tenantCodes(tenants)).as("租户管理 API 应见全部租户，含两个夹具租户")
                .contains(tenantA, tenantB);
        assertThat(catalogSize(tenants)).as("租户列表 total 应等于库中全量").isEqualTo(dbCounts.get("sys_tenant"));

        JsonNode tree = callInTenant("t-1", get("/admin/v1/system/tenants/tree"));
        assertThat(findTenantCode(tree.path("data"), tenantA)).as("租户树应见夹具 A").isNotNull();
        assertThat(findTenantCode(tree.path("data"), tenantB)).as("租户树应见夹具 B").isNotNull();

        for (Map.Entry<String, String> entry : CATALOG_PATHS.entrySet()) {
            if (entry.getKey().equals("/admin/v1/system/tenants")) {
                continue;
            }
            // 目录表 API 自带业务筛选（如 menus 仅 visible），按「两租户视角结果一致且非空」断言
            // 不受租户上下文影响；DB 面精确全量计数由 RlsNonSuperuserIntegrationTest 核对。
            JsonNode inTenantA = callInTenant("t-1", get(entry.getKey())
                    .param("page", "1").param("size", "200"));
            JsonNode inTenantB = callInTenant("t-2", get(entry.getKey())
                    .param("page", "1").param("size", "200"));
            assertThat(inTenantA.path("success").asBoolean())
                    .as("%s 管理 API 应成功：%s", entry.getKey(), inTenantA).isTrue();
            long apiTotal = catalogSize(inTenantA);
            if (dbCounts.get(entry.getValue()) == 0L) {
                assertThat(apiTotal).as("%s 空目录表 API 应为空（无行可隐藏）", entry.getKey()).isZero();
            } else {
                assertThat(apiTotal).as("%s 非空目录表 API 不得因租户上下文返回空集", entry.getKey())
                        .isPositive();
            }
            assertThat(inTenantA.path("data")).as("%s 管理 API 结果不得随租户上下文变化", entry.getKey())
                    .isEqualTo(inTenantB.path("data"));
        }
    }

    // ---------- 支撑 ----------

    private String createTenant(String tenantCode) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 目录可见性夹具");
        JsonNode result = callInTenant("default", post("/admin/v1/system/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean()).as("夹具租户创建应成功：%s", result).isTrue();
        return tenantCode;
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

    private static List<String> tenantCodes(JsonNode body) {
        JsonNode records = body.path("data").path("records");
        List<String> codes = new ArrayList<>();
        for (JsonNode record : records) {
            codes.add(record.path("tenantCode").asText());
        }
        return codes;
    }

    private static String findTenantCode(JsonNode nodes, String tenantCode) {
        if (nodes == null || !nodes.isArray()) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (tenantCode.equals(node.path("tenantCode").asText(null))) {
                return tenantCode;
            }
            String found = findTenantCode(node.path("children"), tenantCode);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static long catalogSize(JsonNode body) {
        JsonNode data = body.path("data");
        if (data.path("total").isNumber()) {
            return data.path("total").asLong();
        }
        JsonNode records = data.path("records");
        if (records.isArray()) {
            return records.size();
        }
        return data.isArray() ? data.size() : 0L;
    }








}
