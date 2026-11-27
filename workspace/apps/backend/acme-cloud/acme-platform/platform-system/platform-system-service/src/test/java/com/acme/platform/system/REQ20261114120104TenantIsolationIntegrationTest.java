package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261114-120104 多租户隔离回归（platform-system）服务域集成测试（Testcontainers PG，装配口径同
 * {@code REQ20261114120104DeptApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-6（确认记录④：部门/岗位为租户内资源，沿用 TenantContext 行级隔离）——
 * 跨租户部门不可见不可操作（树不可见、跨租户改/删失败且记录完好、本租户可操作）；
 * 跨租户岗位不可见不可操作；跨租户用户不可见（既有 TenantContext 回归，HTTP 面）；
 * 跨租户 deptId 入参 fail-closed（本租户视角不可解析他租户部门 → 空集）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>跨租户令牌以同 userId + 不同 tenant claim 签发（InternalTokenAuthFilter 从 token claims 绑定
 *       TenantContext）；tenant-a/tenant-b 无 sys_tenant 种子行——用户表直插先例（SystemIntegrationTest
 *       双租户用例）证明无外键强制——AMB-1。</li>
 *   <li>跨租户操作失败断言 code≠0（不可见表现为 not-found/拒绝类业务码，不钉死具体码）；
 *       super_admin 权限短路跨租户有效（SystemIntegrationTest tenant-a 上下文先例）——AMB-2。</li>
 *   <li>跨租户 deptId 入参期望 fail-closed 空集（对齐 missingTenantReadYieldsEmptyFailClosed 口径），
 *       断言 total=0；若实现以「忽略未知 deptId」为口径则按裁决对齐——AMB-3。</li>
 *   <li>部门/岗位字段命名双键兼容（同 REQ20261114120104DeptApiIntegrationTest AMB-2/AMB-3）。</li>
 * </ul>
 */
class REQ20261114120104TenantIsolationIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TENANT_A = "req120104-tenant-a";
    private static final String TENANT_B = "req120104-tenant-b";

    private static final String TENANT_A_USER = "req120104-tenant-a-user";
    private static final String DEFAULT_USER = "req120104-default-user";

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
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_user_role where user_id in "
                + "(select id from sys_user where username like 'req120104-%')");
        jdbcTemplate.update("delete from sys_user where username like 'req120104-%'");
        jdbcTemplate.update("delete from sys_dept");
        jdbcTemplate.update("delete from sys_position");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-6：跨租户部门不可见、不可操作；本租户可操作 ----------

    @Test
    void should_hideAndProtectOtherTenantDept_when_crossTenantDeptAccess() throws Exception {
        String deptId = createDeptInTenant(TENANT_A, "req-120104-tenant-a-dept");

        // 租户 B 树不可见租户 A 部门；租户 A 自己可见
        assertThat(findDeptId(callInTenant(TENANT_B, get("/admin/v1/system/depts")), deptId))
                .as("租户 B 不应见租户 A 部门").isFalse();
        assertThat(findDeptId(callInTenant(TENANT_A, get("/admin/v1/system/depts")), deptId))
                .as("租户 A 应见自己的部门").isTrue();

        // 租户 B 跨租户改/删 → 统一失败且记录完好（不可操作）
        JsonNode crossUpdate = callInTenant(TENANT_B, put("/admin/v1/system/depts/{id}", deptId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(deptBody("req-120104-tenant-a-dept-hacked"))));
        assertThat(crossUpdate.path("success").asBoolean()).isFalse();
        assertThat(crossUpdate.path("code").asText()).isNotEqualTo("0");
        JsonNode crossDelete = callInTenant(TENANT_B, delete("/admin/v1/system/depts/{id}", deptId));
        assertThat(crossDelete.path("success").asBoolean()).isFalse();
        assertThat(crossDelete.path("code").asText()).isNotEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_dept where id=?", Long.class, Long.parseLong(deptId))).isEqualTo(1L);

        // 租户 A 本租户可操作（删除成功 → 清理兼验证）
        JsonNode ownDelete = callInTenant(TENANT_A, delete("/admin/v1/system/depts/{id}", deptId));
        assertThat(ownDelete.path("code").asText()).as("租户 A 应可删除自己的部门").isEqualTo("0");
    }

    // ---------- AC-6：跨租户岗位不可见、不可操作 ----------

    @Test
    void should_hideAndProtectOtherTenantPosition_when_crossTenantPositionAccess() throws Exception {
        String positionId = createPositionInTenant(TENANT_A, "req-120104-tenant-a-pos", "req-120104-ta-pos-code");

        // 租户 B 分页不可见租户 A 岗位
        JsonPageAssert.of(callInTenant(TENANT_B, get("/admin/v1/system/positions")
                        .param("page", "1").param("size", "50")))
                .assertDoesNotContainId(positionId, "租户 B 不应见租户 A 岗位");

        // 租户 B 跨租户改/删 → 统一失败且记录完好
        JsonNode crossUpdate = callInTenant(TENANT_B, put("/admin/v1/system/positions/{id}", positionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(positionBody("req-120104-tenant-a-pos-hacked"))));
        assertThat(crossUpdate.path("success").asBoolean()).isFalse();
        assertThat(crossUpdate.path("code").asText()).isNotEqualTo("0");
        JsonNode crossDelete = callInTenant(TENANT_B, delete("/admin/v1/system/positions/{id}", positionId));
        assertThat(crossDelete.path("success").asBoolean()).isFalse();
        assertThat(crossDelete.path("code").asText()).isNotEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_position where id=?", Long.class, Long.parseLong(positionId)))
                .isEqualTo(1L);

        // 租户 A 本租户可操作
        assertThat(callInTenant(TENANT_A, delete("/admin/v1/system/positions/{id}", positionId))
                .path("code").asText()).as("租户 A 应可删除自己的岗位").isEqualTo("0");
    }

    // ---------- AC-6：跨租户用户不可见（既有 TenantContext 回归，HTTP 面） ----------

    @Test
    void should_notListOtherTenantUsers_when_crossTenantUserQuery() throws Exception {
        seedUser(TENANT_A_USER, TENANT_A);
        seedUser(DEFAULT_USER, "default");

        // 租户 B 列表既不见租户 A 用户也不见默认租户用户
        JsonNode pageOfB = callInTenant(TENANT_B, get("/admin/v1/system/users")
                .param("page", "1").param("size", "50"));
        assertThat(containsUsername(pageOfB, TENANT_A_USER)).as("租户 B 不应见租户 A 用户").isFalse();
        assertThat(containsUsername(pageOfB, DEFAULT_USER)).as("租户 B 不应见默认租户用户").isFalse();

        // 租户 A 列表见自己用户、不见默认租户用户
        JsonNode pageOfA = callInTenant(TENANT_A, get("/admin/v1/system/users")
                .param("page", "1").param("size", "50"));
        assertThat(containsUsername(pageOfA, TENANT_A_USER)).as("租户 A 应见自己用户").isTrue();
        assertThat(containsUsername(pageOfA, DEFAULT_USER)).as("租户 A 不应见默认租户用户").isFalse();
    }

    // ---------- AC-6：跨租户 deptId 入参 fail-closed（他租户部门在本租户视角不可解析 → 空集） ----------

    @Test
    void should_yieldEmptyResult_when_crossTenantDeptIdFilter() throws Exception {
        String deptId = createDeptInTenant(TENANT_A, "req-120104-crossfilter-dept");
        seedUser(TENANT_A_USER, TENANT_A);
        // 租户 A 用户挂到租户 A 部门（本租户 deptId 筛选应可命中）
        jdbcTemplate.update("update sys_user set dept_id=? where username=?", Long.parseLong(deptId), TENANT_A_USER);

        // 默认租户视角：deptId 指向他租户部门 → fail-closed 空集（AMB-3）
        JsonNode pageOfDefault = callInTenant("default", get("/admin/v1/system/users")
                .param("page", "1").param("size", "50")
                .param("deptId", deptId));
        assertThat(pageOfDefault.path("data").path("total").asLong())
                .as("跨租户 deptId 筛选应 fail-closed 空集（AMB-3）").isZero();
        assertThat(containsUsername(pageOfDefault, TENANT_A_USER)).isFalse();
        assertThat(containsUsername(pageOfDefault, DEFAULT_USER)).isFalse();

        // 租户 A 本租户视角：deptId 筛选正常命中本租户成员
        JsonNode pageOfA = callInTenant(TENANT_A, get("/admin/v1/system/users")
                .param("page", "1").param("size", "50")
                .param("deptId", deptId));
        assertThat(containsUsername(pageOfA, TENANT_A_USER)).as("本租户 deptId 筛选应命中本租户成员").isTrue();
    }

    // ---------- 支撑 ----------

    private String createDeptInTenant(String tenant, String name) throws Exception {
        JsonNode result = callInTenant(tenant, post("/admin/v1/system/depts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(deptBody(name))));
        assertThat(result.path("code").asText()).as("租户内新建部门应成功").isEqualTo("0");
        return result.path("data").path("id").asText();
    }

    private String createPositionInTenant(String tenant, String name, String code) throws Exception {
        JsonNode result = callInTenant(tenant, post("/admin/v1/system/positions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(positionBody(name, code))));
        assertThat(result.path("code").asText()).as("租户内新建岗位应成功").isEqualTo("0");
        return result.path("data").path("id").asText();
    }

    private static Map<String, Object> deptBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", 0);
        body.put("deptName", name);
        body.put("name", name);
        body.put("sort", 1);
        body.put("status", "ENABLED");
        return body;
    }

    private static Map<String, Object> positionBody(String name, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionName", name);
        body.put("name", name);
        body.put("positionCode", code);
        body.put("code", code);
        body.put("status", "ENABLED");
        return body;
    }

    /** 仅改名的跨租户修改体（编码沿用建出值，保证校验合法、失败仅因租户隔离）。 */
    private static Map<String, Object> positionBody(String name) {
        return positionBody(name, "req-120104-ta-pos-code");
    }

    private void seedUser(String username, String tenantId) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              dept_id, position_id, created_at, created_by, updated_at, updated_by,
                                              version, deleted)
                        values (?, ?, ?, ?, null, null, 'ENABLED', false, null, null,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                Math.abs(System.identityHashCode(username)) & 0x7FFFFFFFL | 0x500000000L, tenantId,
                username, username);
    }

    private JsonNode callInTenant(String tenant, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", tenant, Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static boolean findDeptId(JsonNode body, String id) {
        JsonNode data = body.path("data");
        JsonNode nodes = data.isArray() ? data : data.has("records") ? data.get("records") : data;
        return containsNodeById(nodes, id);
    }

    private static boolean containsNodeById(JsonNode nodes, String id) {
        if (nodes == null || !(nodes.isArray() || nodes.isObject())) {
            return false;
        }
        for (JsonNode node : nodes) {
            if (id.equals(node.path("id").asText()) || containsNodeById(node.path("children"), id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUsername(JsonNode pageBody, String username) {
        for (JsonNode record : pageBody.path("data").path("records")) {
            if (username.equals(record.path("username").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 极简分页断言包装（岗位分页 id 存在性）。 */
    private record JsonPageAssert(JsonNode body) {
        static JsonPageAssert of(JsonNode body) {
            return new JsonPageAssert(body);
        }

        void assertDoesNotContainId(String id, String as) {
            for (JsonNode record : body.path("data").path("records")) {
                assertThat(record.path("id").asText()).as(as).isNotEqualTo(id);
            }
        }
    }
}
