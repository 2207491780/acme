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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261114-120104 用户列表数据范围矩阵 + 用户部门/岗位扩展（platform-system）服务域集成测试
 * （Testcontainers PG，装配口径同 {@code REQ20261114120104DeptApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-4 数据范围矩阵核心——scope=ALL/DEPT/DEPT_AND_CHILD/SELF/CUSTOM 五档查用户列表可见集
 * （预置两级部门树 + 各档角色用户；CUSTOM 依赖 sys_role_dept 关联，经 PUT data-scope 端点端到端设置）；
 * deptId 入参含子部门递归；用户创建/编辑接受 deptId/positionId（契约「用户扩展」，既有端点改造）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>可见集断言用标记用户 username 的 contains/doesNotContain（非精确计数）：种子库存在 V5 demo
 *       账号等预置 sys_user，精确 total 会与之耦合——AMB-1。</li>
 *   <li>各档 requester 角色经 JdbcTemplate 直插 sys_role（列集=V1 基线+契约 V9 明示 data_scope），
 *       并同时种子 sys_user_role（DB 解析路径）与令牌 role claims（claims 解析路径）——两条
 *       scope 解析路径取数一致，实现任选其一均可驱动——AMB-2。</li>
 *   <li>requester 角色仅绑定 system:user:list 权限点（id 无关子查询插入），scope 过滤即接口本身行为——AMB-3。</li>
 *   <li>用户列表请求参数 page/size（PageQuery 惯例）、deptId（契约明示）；响应 data.records（分页惯例）——AMB-4。</li>
 *   <li>用户创建请求体：契约只给「接受 deptId/positionId」语义，其余字段（username/nickname）按既有
 *       AdminUserCreate 形态假定，roles 字段名未指明故省略——AMB-5。</li>
 *   <li>用户编辑请求体：详情响应含 version（乐观锁惯例）则回传；nickname/email/phone 按
 *       REQ20261114120102 updateBody 同款「含则回传」口径——AMB-6。</li>
 *   <li>受限档（DEPT/DEPT_AND_CHILD/CUSTOM）可见集的判定基准=requester 自身部门经 V9 列
 *       （sys_user.dept_id）解析；CUSTOM 部门集经 PUT data-scope 端点写入 sys_role_dept（AC-3 端到端）。</li>
 *   <li>无租户入参时列表默认本租户全量按 scope 过滤；scope 过滤与 deptId 过滤的叠加语义仅在全量档
 *       （ALL）下验证（不受 scope 二次裁剪干扰）——受限档叠加过滤不设用例——AMB-7。</li>
 * </ul>
 */
class REQ20261114120104UserDeptScopeIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 标记用户固定 id（高位段避开 V2/V5 种子，teardown 另按 username 前缀兜底清理）。 */
    private static final long U_D1 = 912001L;
    private static final long U_D2 = 912002L;
    private static final long U_D3 = 912003L;
    private static final long U_NODEPT = 912004L;
    private static final long REQ_ALL = 912011L;
    private static final long REQ_DEPT = 912012L;
    private static final long REQ_DC = 912013L;
    private static final long REQ_SELF = 912014L;
    private static final long REQ_CUSTOM = 912015L;

    private static final String ROLE_ALL = "req120104_u_all";
    private static final String ROLE_DEPT = "req120104_u_dept";
    private static final String ROLE_DC = "req120104_u_dc";
    private static final String ROLE_SELF = "req120104_u_self";
    private static final String ROLE_CUSTOM = "req120104_u_custom";

    private static final String U_D1_NAME = "req120104-marker-d1";
    private static final String U_D2_NAME = "req120104-marker-d2";
    private static final String U_D3_NAME = "req120104-marker-d3";
    private static final String U_NODEPT_NAME = "req120104-marker-nodept";
    private static final String REQ_ALL_NAME = "req120104-requester-all";
    private static final String REQ_DEPT_NAME = "req120104-requester-dept";
    private static final String REQ_DC_NAME = "req120104-requester-dc";
    private static final String REQ_SELF_NAME = "req120104-requester-self";
    private static final String REQ_CUSTOM_NAME = "req120104-requester-custom";

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    private String dept1;
    private String dept2;
    private String dept3;
    private String position1;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        // 部门树/岗位经真实端点建出（sys_dept/sys_position 列名实现未定，不直插）
        dept1 = createDept("req-120104-userscope-d1", 0L);
        dept2 = createDept("req-120104-userscope-d2", Long.parseLong(dept1));
        dept3 = createDept("req-120104-userscope-d3", 0L);
        position1 = createPosition("req-120104-userscope-p1", "req-120104-userscope-p1-code");

        // 标记用户：D1/D2/D3 成员 + 无部门用户（sys_user.dept_id 为契约 V9 明示列）
        seedUser(U_D1, U_D1_NAME, Long.parseLong(dept1));
        seedUser(U_D2, U_D2_NAME, Long.parseLong(dept2));
        seedUser(U_D3, U_D3_NAME, Long.parseLong(dept3));
        seedUser(U_NODEPT, U_NODEPT_NAME, null);
        // 各档 requester（均挂 D1，便于 DEPT/DC/CUSTOM 档口径一致）
        seedUser(REQ_ALL, REQ_ALL_NAME, Long.parseLong(dept1));
        seedUser(REQ_DEPT, REQ_DEPT_NAME, Long.parseLong(dept1));
        seedUser(REQ_DC, REQ_DC_NAME, Long.parseLong(dept1));
        seedUser(REQ_SELF, REQ_SELF_NAME, Long.parseLong(dept1));
        seedUser(REQ_CUSTOM, REQ_CUSTOM_NAME, Long.parseLong(dept1));

        // 五档角色 + 用户绑定 + system:user:list 权限绑定（id 无关子查询）
        long allRoleId = seedRole(912101L, ROLE_ALL, "ALL");
        long deptRoleId = seedRole(912102L, ROLE_DEPT, "DEPT");
        long dcRoleId = seedRole(912103L, ROLE_DC, "DEPT_AND_CHILD");
        long selfRoleId = seedRole(912104L, ROLE_SELF, "SELF");
        long customRoleId = seedRole(912105L, ROLE_CUSTOM, null); // CUSTOM 于用例内经端点设置
        bindUserRole(REQ_ALL, allRoleId);
        bindUserRole(REQ_DEPT, deptRoleId);
        bindUserRole(REQ_DC, dcRoleId);
        bindUserRole(REQ_SELF, selfRoleId);
        bindUserRole(REQ_CUSTOM, customRoleId);
        bindRoleUserListPermission(allRoleId);
        bindRoleUserListPermission(deptRoleId);
        bindRoleUserListPermission(dcRoleId);
        bindRoleUserListPermission(selfRoleId);
        bindRoleUserListPermission(customRoleId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_user_role where user_id between 912001 and 912099 "
                + "or role_id in (select id from sys_role where role_code like 'req120104_u_%')");
        jdbcTemplate.update("delete from sys_role_permission where role_id in "
                + "(select id from sys_role where role_code like 'req120104_u_%')");
        jdbcTemplate.update("delete from sys_user where username like 'req120104-%'");
        jdbcTemplate.update("delete from sys_role_dept where role_id in "
                + "(select id from sys_role where role_code like 'req120104_u_%')");
        jdbcTemplate.update("delete from sys_role where role_code like 'req120104_u_%'");
        jdbcTemplate.update("delete from sys_dept");
        jdbcTemplate.update("delete from sys_position");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-4：scope=ALL → 全量 ----------

    @Test
    void should_seeAllMarkerUsers_when_scopeAll() throws Exception {
        JsonNode page = listUsers(REQ_ALL_NAME, Set.of(ROLE_ALL), null);

        assertThat(containsUsername(page, U_D1_NAME)).isTrue();
        assertThat(containsUsername(page, U_D2_NAME)).isTrue();
        assertThat(containsUsername(page, U_D3_NAME)).isTrue();
        assertThat(containsUsername(page, U_NODEPT_NAME)).isTrue();
    }

    // ---------- AC-4：scope=DEPT → 仅本部门 ----------

    @Test
    void should_seeOnlyOwnDeptUsers_when_scopeDept() throws Exception {
        JsonNode page = listUsers(REQ_DEPT_NAME, Set.of(ROLE_DEPT), null);

        assertThat(containsUsername(page, U_D1_NAME)).as("DEPT 档应见本部门成员").isTrue();
        assertThat(containsUsername(page, U_D2_NAME)).as("DEPT 档不应见子部门成员").isFalse();
        assertThat(containsUsername(page, U_D3_NAME)).as("DEPT 档不应见其他部门成员").isFalse();
        assertThat(containsUsername(page, U_NODEPT_NAME)).as("DEPT 档不应见无部门用户").isFalse();
    }

    // ---------- AC-4：scope=DEPT_AND_CHILD → 含子部门 ----------

    @Test
    void should_seeDeptAndChildUsers_when_scopeDeptAndChild() throws Exception {
        JsonNode page = listUsers(REQ_DC_NAME, Set.of(ROLE_DC), null);

        assertThat(containsUsername(page, U_D1_NAME)).as("DEPT_AND_CHILD 档应见本部门成员").isTrue();
        assertThat(containsUsername(page, U_D2_NAME)).as("DEPT_AND_CHILD 档应见子部门成员").isTrue();
        assertThat(containsUsername(page, U_D3_NAME)).as("DEPT_AND_CHILD 档不应见旁支部门成员").isFalse();
        assertThat(containsUsername(page, U_NODEPT_NAME)).as("DEPT_AND_CHILD 档不应见无部门用户").isFalse();
    }

    // ---------- AC-4：scope=SELF → 仅本人 ----------

    @Test
    void should_seeOnlySelf_when_scopeSelf() throws Exception {
        JsonNode page = listUsers(REQ_SELF_NAME, Set.of(ROLE_SELF), null);

        assertThat(containsUsername(page, REQ_SELF_NAME)).as("SELF 档应见本人").isTrue();
        assertThat(containsUsername(page, U_D1_NAME)).as("SELF 档不应见同部门他人").isFalse();
        assertThat(containsUsername(page, U_D2_NAME)).as("SELF 档不应见子部门成员").isFalse();
        assertThat(containsUsername(page, U_D3_NAME)).as("SELF 档不应见其他部门成员").isFalse();
        assertThat(containsUsername(page, U_NODEPT_NAME)).as("SELF 档不应见无部门用户").isFalse();
    }

    // ---------- AC-4：scope=CUSTOM → sys_role_dept 自定义部门集（经 data-scope 端点写入，AC-3 端到端） ----------

    @Test
    void should_seeCustomDeptUsers_when_scopeCustom() throws Exception {
        // 端到端设置 CUSTOM + deptIds=[D1, D3]（AC-3 端点 → sys_role_dept → AC-4 过滤链路）
        JsonNode scoped = call(put("/admin/v1/system/roles/{id}/data-scope", 912105L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("dataScope", "CUSTOM",
                        "deptIds", List.of(Long.parseLong(dept1), Long.parseLong(dept3))))));
        assertThat(scoped.path("code").asText()).as("CUSTOM 数据范围设置应成功").isEqualTo("0");

        JsonNode page = listUsers(REQ_CUSTOM_NAME, Set.of(ROLE_CUSTOM), null);

        assertThat(containsUsername(page, U_D1_NAME)).as("CUSTOM 档应见自定义部门集内成员").isTrue();
        assertThat(containsUsername(page, U_D3_NAME)).as("CUSTOM 档应见自定义部门集内成员").isTrue();
        assertThat(containsUsername(page, U_D2_NAME)).as("CUSTOM 档不应见集合外部门成员").isFalse();
        assertThat(containsUsername(page, U_NODEPT_NAME)).as("CUSTOM 档不应见无部门用户").isFalse();
    }

    // ---------- AC-4/用户扩展：deptId 入参含子部门递归（全量档验证过滤本身，AMB-7） ----------

    @Test
    void should_filterByDeptIdIncludingChildDept_when_deptIdParamWithAllScope() throws Exception {
        JsonNode page = listUsers(REQ_ALL_NAME, Set.of(ROLE_ALL), Long.parseLong(dept1));

        assertThat(containsUsername(page, U_D1_NAME)).as("deptId 筛选应见本部门成员").isTrue();
        assertThat(containsUsername(page, U_D2_NAME)).as("deptId 筛选应含子部门递归成员").isTrue();
        assertThat(containsUsername(page, U_D3_NAME)).as("deptId 筛选不应见旁支部门成员").isFalse();
        assertThat(containsUsername(page, U_NODEPT_NAME)).as("deptId 筛选不应见无部门用户").isFalse();
    }

    // ---------- 用户扩展：创建接受 deptId/positionId 并落库 ----------

    @Test
    void should_persistDeptAndPosition_when_createUserWithDeptFields() throws Exception {
        JsonNode created = call(post("/admin/v1/system/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "username", "req120104-created-user",
                        "nickname", "数据范围建户",
                        "deptId", Long.parseLong(dept1),
                        "positionId", Long.parseLong(position1)))));
        assertThat(created.path("code").asText()).as("带部门/岗位建户应成功（AMB-5）").isEqualTo("0");

        Long deptId = jdbcTemplate.queryForObject(
                "select dept_id from sys_user where username='req120104-created-user'", Long.class);
        Long positionId = jdbcTemplate.queryForObject(
                "select position_id from sys_user where username='req120104-created-user'", Long.class);
        assertThat(deptId).as("deptId 应落 sys_user.dept_id").isEqualTo(Long.parseLong(dept1));
        assertThat(positionId).as("positionId 应落 sys_user.position_id").isEqualTo(Long.parseLong(position1));
    }

    // ---------- 用户扩展：编辑可调整部门并落库 ----------

    @Test
    void should_updateUserDept_when_editUserWithDeptField() throws Exception {
        String userId = createUser("req120104-edit-user", "数据范围改部门", Long.parseLong(dept1));

        // 详情含则回传（version 乐观锁 / nickname/email/phone 惯例回传，AMB-6）
        JsonNode detail = call(get("/admin/v1/system/users/{id}", userId));
        assertThat(detail.path("code").asText()).isEqualTo("0");
        JsonNode data = detail.path("data");

        Map<String, Object> updateBody = new LinkedHashMap<>();
        if (data.hasNonNull("nickname")) {
            updateBody.put("nickname", data.get("nickname").asText());
        }
        if (data.hasNonNull("email")) {
            updateBody.put("email", data.get("email").asText());
        }
        if (data.hasNonNull("phone")) {
            updateBody.put("phone", data.get("phone").asText());
        }
        if (data.hasNonNull("version")) {
            updateBody.put("version", data.get("version"));
        }
        if (data.hasNonNull("roleIds")) {
            updateBody.put("roleIds", data.get("roleIds"));
        } else if (data.hasNonNull("roles")) {
            updateBody.put("roles", data.get("roles"));
        }
        updateBody.put("deptId", Long.parseLong(dept3));

        JsonNode updated = call(put("/admin/v1/system/users/{id}", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody)));
        assertThat(updated.path("code").asText()).as("编辑用户部门应成功").isEqualTo("0");

        Long deptId = jdbcTemplate.queryForObject(
                "select dept_id from sys_user where id=?", Long.class, Long.parseLong(userId));
        assertThat(deptId).as("编辑后 dept_id 应更新为新部门").isEqualTo(Long.parseLong(dept3));
    }

    // ---------- 支撑 ----------

    /** 用户列表查询：roles/tenant 驱动 scope 档；deptId 可空。 */
    private JsonNode listUsers(String requesterName, Set<String> roles, Long deptId) throws Exception {
        Long requesterId = jdbcTemplate.queryForObject(
                "select id from sys_user where username=?", Long.class, requesterName);
        MockHttpServletRequestBuilder request = get("/admin/v1/system/users")
                .param("page", "1").param("size", "50");
        if (deptId != null) {
            request.param("deptId", String.valueOf(deptId));
        }
        JsonNode body = bodyOf(mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                String.valueOf(requesterId), "default", Channel.B, roles)))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn());
        assertThat(body.path("code").asText()).as("用户列表查询应成功").isEqualTo("0");
        return body;
    }

    private static boolean containsUsername(JsonNode pageBody, String username) {
        for (JsonNode record : pageBody.path("data").path("records")) {
            if (username.equals(record.path("username").asText())) {
                return true;
            }
        }
        return false;
    }

    private String createUser(String username, String nickname, long deptId) throws Exception {
        JsonNode created = call(post("/admin/v1/system/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "username", username, "nickname", nickname, "deptId", deptId))));
        assertThat(created.path("code").asText()).isEqualTo("0");
        return created.path("data").path("id").asText();
    }

    private String createDept(String name, long parentId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", parentId);
        body.put("deptName", name);
        body.put("name", name);
        body.put("sort", 1);
        body.put("status", "ENABLED");
        JsonNode result = call(post("/admin/v1/system/depts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("code").asText()).as("新建部门应成功").isEqualTo("0");
        return result.path("data").path("id").asText();
    }

    private String createPosition(String name, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionName", name);
        body.put("name", name);
        body.put("positionCode", code);
        body.put("code", code);
        body.put("status", "ENABLED");
        JsonNode result = call(post("/admin/v1/system/positions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("code").asText()).as("新建岗位应成功").isEqualTo("0");
        return result.path("data").path("id").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        return bodyOf(mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn());
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void seedUser(long id, String username, Long deptId) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              dept_id, position_id, created_at, created_by, updated_at, updated_by,
                                              version, deleted)
                        values (?, 'default', ?, ?, null, null, 'ENABLED', false, ?, null,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                id, username, username, deptId);
    }

    private long seedRole(long roleId, String roleCode, String dataScope) {
        jdbcTemplate.update("""
                        insert into sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                                              data_scope, created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, 'ENABLED', null, false, ?,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                roleId, roleCode, roleCode, dataScope);
        return roleId;
    }

    private void bindUserRole(long userId, long roleId) {
        jdbcTemplate.update("""
                        insert into sys_user_role (id, tenant_id, user_id, role_id, created_at, created_by,
                                                   updated_at, updated_by, version, deleted)
                        values (?, 'default', ?, ?, now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                userId * 10 + 1, userId, roleId);
    }

    private void bindRoleUserListPermission(long roleId) {
        jdbcTemplate.update("""
                        insert into sys_role_permission (id, tenant_id, role_id, permission_id, created_at, created_by,
                                                         updated_at, updated_by, version, deleted)
                        values (?, 'default', ?,
                                (select id from sys_permission where permission_code='system:user:list'),
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                roleId * 10 + 1, roleId);
    }
}
