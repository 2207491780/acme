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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
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
 * REQ-20261114-120104 部门管理（platform-system）服务域集成测试（Testcontainers PG，照抄
 * {@code SystemIntegrationTest}/{@code REQ20261114120102NoticeApiIntegrationTest} 装配口径；
 * 本域不触 Redis pub/sub，容器仅 PG（SystemIntegrationTest 先例）；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1 部门树增删改查（树形返回含禁用态）、删除有子部门 → {@code DEPT_HAS_CHILDREN}、
 * 删除有用户的部门 → {@code DEPT_HAS_USERS}、不存在部门删除失败；AC-7 部门端点无权限统一拒绝；
 * V9 权限点种子冒烟（dept 4 码 + position 4 码 + role:scope 1 码存在且绑定超管，V8 先例口径）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>业务失败断言统一响应体 {@code Result.code != "0"}（不断言 4xx，HTTP 200 语义）。
 *       契约仅给出符号错误码（{@code DEPT_HAS_CHILDREN}/{@code DEPT_HAS_USERS}），wire 三段式码
 *       的模块段/序号在实现交付前不可知（禁读实现、禁猜测）：守卫用例断言 code≠0 且
 *       ≠接口级权限拒绝码 {@code platform-system-07-0001}（PERMISSION_DENIED，操作者持权，
 *       排除「被权限层拒绝」的假阳性），精确 wire 码待 backend 落 SystemErrorCodes 后回填——AMB-1。</li>
 *   <li>部门字段命名未指明：请求体 {@code deptName} 与 {@code name} 双键兼容发送（Boot 宽松绑定
 *       忽略未知键），响应节点按 id 定位、字段名不钉死——AMB-2。</li>
 *   <li>根部门父级取值假定 0（menu 域 parentId=0 先例）——AMB-3。</li>
 *   <li>状态枚举字面量假定 {@code ENABLED}/{@code DISABLED}（sys_user/sys_menu 先例）——AMB-4。</li>
 *   <li>PUT 是否携带乐观锁 version 未指明（工程惯例携带）：树节点/详情含 version 则回传，否则省略——AMB-5。</li>
 *   <li>删除是否软删未指明（审计基类惯例软删）：落库断言以 deleted=false 表达「未被删除」，
 *       软删成功用例断言 deleted=true；若实现为物理删，按实际行为对齐——AMB-6。</li>
 *   <li>「有用户的部门」判定口径未指明（绑定用户含软删用户与否）：用例以存活用户绑定触发守卫，
 *       不钉死软删用户口径——AMB-7。</li>
 *   <li>V9 权限点种子条数：契约端点推演 dept 4 + position 4 + role:scope = 9 码（委派单预期 8 权限点
 *       存在出入）——用例按 containsExactly 9 码断言（与 V8 先例同风格），sys_permission 总数断言留给
 *       backend 交付时更新 SystemIntegrationTest 的 53→新值（AMB-8）。</li>
 *   <li>正向用例以 super_admin 角色令牌驱动（依赖 V9 种子对 super_admin 全量绑定新权限点的既有惯例）；
 *       无权限用例以未绑定角色令牌驱动，仅断言 Result.code≠0（真实拒绝码由安全层定义，同范本口径）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-dept-it-secret-0123456789abcdef"
})
class REQ20261114120104DeptApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 接口级权限拒绝码（SystemErrorCodes.PERMISSION_DENIED，Backend.md 10.5.2 布局）：守卫用例排除假阳性用。 */
    private static final String PERMISSION_DENIED = "platform-system-07-0001";

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
        // 真实鉴权过滤器（内部令牌校验）+ 真实 MVC 拦截器链（含权限判定）；权限走本服务 DB 决策，无需桩件
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：物理清表（绕开租户/软删过滤面）；绑定行先清、种子数据不动
        jdbcTemplate.update("delete from sys_user_role where user_id in "
                + "(select id from sys_user where username like 'req120104-%')");
        jdbcTemplate.update("delete from sys_user where username like 'req120104-%'");
        jdbcTemplate.update("delete from sys_dept");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- V9 权限点种子冒烟（AC-1/AC-7 权限判定的数据前置，V8 先例口径） ----------

    @Test
    void flywaySeedsOrgPermissionCodesAndBindsSuperAdmin() {
        // 契约变更清单：权限点种子 dept/position CRUD、role:scope（条数推演 9 码，见 AMB-8）
        List<String> codes = jdbcTemplate.queryForList(
                "select permission_code from sys_permission where permission_code in ("
                        + "'system:dept:list','system:dept:create','system:dept:update','system:dept:delete',"
                        + "'system:position:list','system:position:create','system:position:update','system:position:delete',"
                        + "'system:role:scope') order by id", String.class);
        assertThat(codes).containsExactly(
                "system:dept:list", "system:dept:create", "system:dept:update", "system:dept:delete",
                "system:position:list", "system:position:create", "system:position:update", "system:position:delete",
                "system:role:scope");

        // V8 先例（种子幂等 + 超管全量绑定）：9 码均须绑定 super_admin（id 无关的 NOT EXISTS 口径）
        Long unboundCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_permission p "
                        + "where p.permission_code in ("
                        + "'system:dept:list','system:dept:create','system:dept:update','system:dept:delete',"
                        + "'system:position:list','system:position:create','system:position:update','system:position:delete',"
                        + "'system:role:scope') "
                        + "and not exists (select 1 from sys_role_permission rp where rp.permission_id = p.id "
                        + "and rp.role_id = (select id from sys_role where role_code='super_admin' and tenant_id='default'))",
                Long.class);
        assertThat(unboundCount).as("V9 新权限点应全部绑定 super_admin").isZero();
    }

    // ---------- AC-1：增/查（树形，含禁用态）/改 生效 ----------

    @Test
    void should_createDeptAndReturnTreeWithDisabledNode_when_deptCrudWithPermission() throws Exception {
        String rootId = createDept("req-120104-root", 0L, 1, "ENABLED");
        String childId = createDept("req-120104-child", Long.parseLong(rootId), 1, "ENABLED");
        String disabledId = createDept("req-120104-disabled", 0L, 9, "DISABLED");

        // 树形返回：根含子节点（嵌套结构而非平铺断言）；含禁用态节点（契约「树形返回（含禁用态）」）
        JsonNode roots = treeRoots(call(get("/admin/v1/system/depts")));
        assertThat(roots.isArray() || roots.has("records")).as("部门树应返回数组/分页结构").isTrue();
        JsonNode root = findNode(treeRoots(call(get("/admin/v1/system/depts"))), rootId);
        assertThat(root).as("新建根部门应出现在树中").isNotNull();
        assertThat(findNode(root.path("children"), childId))
                .as("子部门应嵌套在父节点 children 下").isNotNull();
        assertThat(findNode(call(get("/admin/v1/system/depts")).path("data"), disabledId))
                .as("禁用态部门也应出现在树中（含禁用态）").isNotNull();

        // 编辑（改名 + 排序；version 惯例：节点含则回传，AMB-5）
        JsonNode renamed = call(put("/admin/v1/system/depts/{id}", rootId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(deptBody(Long.parseLong(rootId),
                        "req-120104-root-2", 2, "ENABLED", versionOf(root)))));
        assertThat(renamed.path("code").asText()).isEqualTo("0");

        JsonNode after = findNode(treeRoots(call(get("/admin/v1/system/depts"))), rootId);
        assertThat(after).isNotNull();
        assertThat(nameOf(after)).as("PUT 后树应反映新名称").isEqualTo("req-120104-root-2");
        // 子节点挂接关系不被改名破坏
        assertThat(findNode(after.path("children"), childId)).isNotNull();
    }

    // ---------- AC-1 边界：删除有子部门的部门 → DEPT_HAS_CHILDREN ----------

    @Test
    void should_rejectDeleteWithGuardCode_when_deleteDeptHavingChild() throws Exception {
        String rootId = createDept("req-120104-guard-parent", 0L, 1, "ENABLED");
        String childId = createDept("req-120104-guard-child", Long.parseLong(rootId), 1, "ENABLED");

        JsonNode rejected = call(delete("/admin/v1/system/depts/{id}", rootId));

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("守卫拒绝应为业务失败码（≠0），且非权限拒绝（操作者持权，AMB-1）")
                .isNotEqualTo("0").isNotEqualTo(PERMISSION_DENIED);
        // 记录未被删除（AMB-6：软删口径下 deleted=false；物理删口径下行为一致）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_dept where id=?", Long.class, Long.parseLong(rootId))).isEqualTo(1L);

        // 先删子再删父：守卫释放后可删（确认记录③ 语义完整性）
        assertThat(call(delete("/admin/v1/system/depts/{id}", childId))
                .path("code").asText()).isEqualTo("0");
        assertThat(call(delete("/admin/v1/system/depts/{id}", rootId))
                .path("code").asText()).isEqualTo("0");
    }

    // ---------- AC-1 边界：删除有用户的部门 → DEPT_HAS_USERS ----------

    @Test
    void should_rejectDeleteWithGuardCode_when_deleteDeptHavingUser() throws Exception {
        String deptId = createDept("req-120104-user-dept", 0L, 1, "ENABLED");
        long boundUserId = seedDeptUser("req120104-dept-user", Long.parseLong(deptId));

        JsonNode rejected = call(delete("/admin/v1/system/depts/{id}", deptId));

        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText())
                .as("有用户的部门删除应业务失败（≠0）且非权限拒绝（AMB-1）")
                .isNotEqualTo("0").isNotEqualTo(PERMISSION_DENIED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_dept where id=?", Long.class, Long.parseLong(deptId))).isEqualTo(1L);
        // 用户部门绑定不受影响
        assertThat(jdbcTemplate.queryForObject(
                "select dept_id from sys_user where id=?", Long.class, boundUserId))
                .as("被守卫拒绝后用户 dept 绑定应保持").isEqualTo(Long.parseLong(deptId));
    }

    // ---------- AC-1 边界：不存在部门 update/delete → 统一失败（契约未点名错误码，仅断言 ≠0，AMB-1） ----------

    @Test
    void should_failWithBusinessCode_when_updateOrDeleteMissingDept() throws Exception {
        JsonNode deleted = call(delete("/admin/v1/system/depts/{id}", "99999999"));
        assertThat(deleted.path("success").asBoolean()).isFalse();
        assertThat(deleted.path("code").asText()).isNotEqualTo("0");

        JsonNode updated = call(put("/admin/v1/system/depts/{id}", "99999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(deptBody(0L, "x", 1, "ENABLED", null))));
        assertThat(updated.path("success").asBoolean()).isFalse();
        assertThat(updated.path("code").asText()).isNotEqualTo("0");
    }

    // ---------- AC-7：部门端点无权限 → 统一拒绝（接口级权限面） ----------

    @Test
    void should_rejectAllDeptEndpoints_when_noPermission() throws Exception {
        List<AbstractMockHttpServletRequestBuilder<?>> endpoints = List.of(
                post("/admin/v1/system/depts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(deptBody(0L, "x", 1, "ENABLED", null))),
                get("/admin/v1/system/depts"),
                put("/admin/v1/system/depts/{id}", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(deptBody(0L, "x", 1, "ENABLED", null))),
                delete("/admin/v1/system/depts/{id}", "1"));

        for (AbstractMockHttpServletRequestBuilder<?> endpoint : endpoints) {
            JsonNode body = callWithNoPermission(endpoint);
            assertThat(body.path("success").asBoolean())
                    .as("缺少权限时应统一拒绝：%s", endpoint.toString()).isFalse();
            assertThat(body.path("code").asText())
                    .as("业务失败以 Result.code≠0 表达（勿断言 4xx）：%s", endpoint.toString())
                    .isNotEqualTo("0");
        }
    }

    // ---------- 支撑 ----------

    private String createDept(String name, long parentId, int sort, String status) {
        try {
            JsonNode body = call(post("/admin/v1/system/depts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(deptBody(parentId, name, sort, status, null))));
            assertThat(body.path("code").asText()).as("新建部门应成功").isEqualTo("0");
            return body.path("data").path("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("新建部门请求失败", ex);
        }
    }

    /**
     * 部门请求体：契约只给「名称/父级/排序/状态」语义未给字段名（AMB-2）——deptName/name 双键兼容发送，
     * Boot 宽松绑定忽略未知键；version 惯例（AMB-5）由调用方决定是否携带。
     */
    private static Map<String, Object> deptBody(long parentId, String name, int sort, String status, Object version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", parentId);
        body.put("deptName", name);
        body.put("name", name);
        body.put("sort", sort);
        body.put("status", status);
        if (version != null) {
            body.put("version", version);
        }
        return body;
    }

    /** 预置绑定部门的存活用户（绕开用户创建端点依赖，dept_id 列为契约 V9 明示变更）。 */
    private long seedDeptUser(String username, long deptId) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              dept_id, position_id, created_at, created_by, updated_at, updated_by,
                                              version, deleted)
                        values (?, 'default', ?, ?, null, null, 'ENABLED', false, ?, null,
                                now(), 'req120104-it', now(), 'req120104-it', 0, false)
                        """,
                System.identityHashCode(username) & 0x7FFFFFFF | 0x40000000L, username, username, deptId);
        Long id = jdbcTemplate.queryForObject(
                "select id from sys_user where username=?", Long.class, username);
        return id == null ? -1L : id;
    }

    private JsonNode callWithNoPermission(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return call(request, Set.of("req120104-unbound-role"), "default");
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return call(request, Set.of("super_admin"), "default");
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request, Set<String> roles, String tenant) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token("1", tenant, roles))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return bodyOf(result);
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String token(String userId, String tenant, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, tenant, Channel.B, roles));
    }

    /** 树形数据定位：数组 / {records} / 包装对象三兼容。 */
    private static JsonNode treeRoots(JsonNode body) {
        JsonNode data = body.path("data");
        if (data.isArray()) {
            return data;
        }
        return data.has("records") ? data.get("records") : data;
    }

    /** 递归按 id 查节点（children 缺省视为空）。 */
    private static JsonNode findNode(JsonNode nodes, String id) {
        if (nodes == null || !(nodes.isArray() || nodes.isObject())) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
            JsonNode nested = findNode(node.path("children"), id);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** 树节点版本号（乐观锁回传惯例，AMB-5）。 */
    private static Object versionOf(JsonNode node) {
        return node != null && node.hasNonNull("version") ? node.get("version") : null;
    }

    /** 部门名称字段兼容读取（deptName 优先，AMB-2）。 */
    private static String nameOf(JsonNode node) {
        if (node.hasNonNull("deptName")) {
            return node.get("deptName").asText();
        }
        return node.hasNonNull("name") ? node.get("name").asText() : null;
    }
}
