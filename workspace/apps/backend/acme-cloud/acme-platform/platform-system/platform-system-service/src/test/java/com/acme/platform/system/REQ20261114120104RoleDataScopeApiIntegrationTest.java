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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261114-120104 角色数据范围（platform-system）服务域集成测试（Testcontainers PG，装配口径同
 * {@code REQ20261114120104DeptApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-3 scope=CUSTOM 未传 deptIds 校验拒绝、传合法 deptIds → {@code sys_role_dept} 落库；
 * 五档枚举可设置（ALL/DEPT/DEPT_AND_CHILD/SELF）；非法枚举拒绝（{@code DATA_SCOPE_INVALID}）；
 * 重复设置整体替换（CUSTOM 部门集）；AC-7 数据范围端点无权限统一拒绝。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>请求体字段假定 {@code dataScope}（枚举字面量 ALL/DEPT/DEPT_AND_CHILD/SELF/CUSTOM 为契约明示）
 *       + {@code deptIds}（契约明示）——AMB-1。</li>
 *   <li>契约仅给符号码 {@code DATA_SCOPE_INVALID}，wire 三段式码不可知（禁读实现、禁猜测）：
 *       校验拒绝用例断言 code≠0 且 ≠权限拒绝码 {@code platform-system-07-0001}——AMB-2。</li>
 *   <li>非 CUSTOM 档是否清空既有 sys_role_dept 未指明：仅在 CUSTOM 档断言关联行，非 CUSTOM 档不断言——AMB-3。</li>
 *   <li>重复设置 CUSTOM 部门集假定整体替换（角色绑定 bindPermissions 整体替换先例 10.3.1）——AMB-4。</li>
 *   <li>测试角色经 JdbcTemplate 直插（sys_role 列集为 V1 基线 + 契约 V9 明示 data_scope 列，避免依赖
 *       角色创建端点请求体形态）；固定 id + role_code 前缀 req120104，teardown 精确清理。</li>
 *   <li>deptIds 引用的部门经部门创建端点真实建出（AC-1 正向路径），不直插 sys_dept（其列名实现未定）。</li>
 *   <li>正向用例以 super_admin 角色令牌驱动；无权限用例以未绑定角色令牌驱动（同范本口径）。</li>
 * </ul>
 */
class REQ20261114120104RoleDataScopeApiIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PERMISSION_DENIED = "platform-system-07-0001";

    /** 测试角色 id 段（高位段避开 V2/V5 种子；teardown 按 role_code 前缀精确清理）。 */
    private static final AtomicLong ROLE_ID_SEQ = new AtomicLong(912501L);

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
        jdbcTemplate.update("delete from sys_role_dept where role_id in "
                + "(select id from sys_role where role_code like 'req120104-scope-%')");
        jdbcTemplate.update("delete from sys_role where role_code like 'req120104-scope-%'");
        jdbcTemplate.update("delete from sys_dept");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-3：CUSTOM + 合法 deptIds → data_scope 与 sys_role_dept 落库 ----------

    @Test
    void should_persistCustomScopeAndRoleDeptRows_when_setDataScopeWithDeptIds() throws Exception {
        long roleId = seedRole("req120104-scope-custom", null);
        String dept1 = createDept("req-120104-scope-d1");
        String dept2 = createDept("req-120104-scope-d2");

        JsonNode result = call(put("/admin/v1/system/roles/{id}/data-scope", roleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "dataScope", "CUSTOM", "deptIds", List.of(Long.parseLong(dept1), Long.parseLong(dept2))))));

        assertThat(result.path("code").asText()).isEqualTo("0");
        // sys_role.data_scope 落库（契约 V9 明示列）
        assertThat(jdbcTemplate.queryForObject(
                "select data_scope from sys_role where id=?", String.class, roleId)).isEqualTo("CUSTOM");
        // sys_role_dept 关联精确落库（两部门各一行，无多余行）
        List<Long> boundDeptIds = jdbcTemplate.queryForList(
                "select dept_id from sys_role_dept where role_id=? order by dept_id", Long.class, roleId);
        assertThat(boundDeptIds).containsExactly(Long.parseLong(dept1), Long.parseLong(dept2));
    }

    // ---------- AC-3：CUSTOM 未传 deptIds → 校验拒绝（DATA_SCOPE_INVALID） ----------

    @Test
    void should_rejectCustomScopeWithoutDeptIds_when_deptIdsMissing() throws Exception {
        long roleId = seedRole("req120104-scope-nodpts", null);
        String dept1 = createDept("req-120104-scope-guard-d1");

        // 缺 deptIds 键
        JsonNode missing = call(put("/admin/v1/system/roles/{id}/data-scope", roleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("dataScope", "CUSTOM"))));
        assertThat(missing.path("success").asBoolean()).isFalse();
        assertThat(missing.path("code").asText())
                .as("CUSTOM 缺 deptIds 应校验拒绝（≠0）且非权限拒绝（AMB-2）")
                .isNotEqualTo("0").isNotEqualTo(PERMISSION_DENIED);

        // 空 deptIds 列表同样拒绝（「未传」含空集口径）
        JsonNode empty = call(put("/admin/v1/system/roles/{id}/data-scope", roleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("dataScope", "CUSTOM", "deptIds", List.of()))));
        assertThat(empty.path("success").asBoolean()).isFalse();
        assertThat(empty.path("code").asText()).isNotEqualTo("0");

        // 拒绝后状态不变：data_scope 未改、未产生关联行（以「存在但未被 CUSTOM 选中」的部门为参照，AMB-3）
        assertThat(jdbcTemplate.queryForObject(
                "select data_scope from sys_role where id=?", String.class, roleId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_dept where role_id=?", Long.class, roleId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_dept where id=?", Long.class, Long.parseLong(dept1))).isEqualTo(1L);
    }

    // ---------- AC-3：非法枚举值 → 校验拒绝（DATA_SCOPE_INVALID） ----------

    @Test
    void should_rejectUnknownScopeEnum_when_setDataScopeInvalidValue() throws Exception {
        long roleId = seedRole("req120104-scope-badenum", null);

        JsonNode rejected = call(put("/admin/v1/system/roles/{id}/data-scope", roleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("dataScope", "EVERYTHING", "deptIds", List.of()))));

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("非法枚举应校验拒绝（≠0）且非权限拒绝（AMB-2）")
                .isNotEqualTo("0").isNotEqualTo(PERMISSION_DENIED);
        assertThat(jdbcTemplate.queryForObject(
                "select data_scope from sys_role where id=?", String.class, roleId)).isNull();
    }

    // ---------- AC-3：非 CUSTOM 档可设置（ALL/DEPT/DEPT_AND_CHILD/SELF） ----------

    @Test
    void should_setPlainScopeValues_when_setDataScopeEachEnum() throws Exception {
        long roleId = seedRole("req120104-scope-plain", null);

        for (String scope : List.of("ALL", "DEPT", "DEPT_AND_CHILD", "SELF")) {
            JsonNode result = call(put("/admin/v1/system/roles/{id}/data-scope", roleId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("dataScope", scope))));
            assertThat(result.path("code").asText())
                    .as("设置 scope=%s 应成功", scope).isEqualTo("0");
            assertThat(jdbcTemplate.queryForObject(
                    "select data_scope from sys_role where id=?", String.class, roleId))
                    .as("scope=%s 应落库", scope).isEqualTo(scope);
        }
    }

    // ---------- AC-3：重复设置 CUSTOM 部门集 → 整体替换（AMB-4） ----------

    @Test
    void should_replaceCustomDeptSet_when_resetDataScopeWithDifferentDeptIds() throws Exception {
        long roleId = seedRole("req120104-scope-replace", null);
        String dept1 = createDept("req-120104-scope-rep-d1");
        String dept2 = createDept("req-120104-scope-rep-d2");

        assertThat(call(put("/admin/v1/system/roles/{id}/data-scope", roleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("dataScope", "CUSTOM",
                        "deptIds", List.of(Long.parseLong(dept1))))))
                .path("code").asText()).isEqualTo("0");
        assertThat(call(put("/admin/v1/system/roles/{id}/data-scope", roleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("dataScope", "CUSTOM",
                        "deptIds", List.of(Long.parseLong(dept2))))))
                .path("code").asText()).isEqualTo("0");

        List<Long> boundDeptIds = jdbcTemplate.queryForList(
                "select dept_id from sys_role_dept where role_id=?", Long.class, roleId);
        assertThat(boundDeptIds).as("重复设置应整体替换为新部门集").containsExactly(Long.parseLong(dept2));
    }

    // ---------- AC-7：数据范围端点无权限 → 统一拒绝（system:role:scope） ----------

    @Test
    void should_rejectDataScopeEndpoint_when_noPermission() throws Exception {
        long roleId = seedRole("req120104-scope-noperm", null);

        JsonNode body = call(put("/admin/v1/system/roles/{id}/data-scope", roleId), Set.of("req120104-unbound-role"));
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isNotEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "select data_scope from sys_role where id=?", String.class, roleId)).isNull();
    }

    // ---------- AC-3 边界：不存在角色设置数据范围 → 统一失败 ----------

    @Test
    void should_failWithBusinessCode_when_setDataScopeMissingRole() throws Exception {
        JsonNode body = call(put("/admin/v1/system/roles/{id}/data-scope", "99999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("dataScope", "ALL"))));
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isNotEqualTo("0");
    }

    // ---------- 支撑 ----------

    private long seedRole(String roleCode, String dataScope) {
        long roleId = ROLE_ID_SEQ.getAndIncrement();
        jdbcTemplate.update("""
                        insert into sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                                              data_scope, created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, 'ENABLED', null, false, ?,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                roleId, roleCode, roleCode, dataScope);
        return roleId;
    }

    private String createDept(String name) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentId", 0);
            body.put("deptName", name);
            body.put("name", name);
            body.put("sort", 1);
            body.put("status", "ENABLED");
            JsonNode result = call(post("/admin/v1/system/depts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(body)));
            assertThat(result.path("code").asText()).as("新建部门应成功").isEqualTo("0");
            return result.path("data").path("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("新建部门请求失败", ex);
        }
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, Set.of("super_admin"));
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Set<String> roles) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, roles)))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
