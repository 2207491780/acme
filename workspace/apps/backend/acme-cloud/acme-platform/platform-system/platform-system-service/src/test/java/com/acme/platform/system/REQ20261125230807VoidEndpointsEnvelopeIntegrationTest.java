package com.acme.platform.system;

import com.acme.common.context.Channel;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261125-230807（AC-5/AC-20）void 端点信封化 wire 契约测试（Testcontainers PG，装配口径照抄
 * {@code REQ20261122233426TenantInitTemplateTesterTest}/{@code REQ20261120214653PermissionFilterApiIntegrationTest}；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>契约（API 节「void 端点信封化」+ 确认记录① + 钉死事实①）：9 个 void 端点改返回
 * {@code Result<Void>}，成功 wire=HTTP 200 + Result 信封 {@code code="0"}（空体为兼容口径，
 * 信封化后成功响应不再出现 0 字节体）；业务失败=统一响应体 Result.code≠0（勿断言 4xx）。
 * auth 域 {@code PUT /admin/v1/auth/credentials/users/{userId}/password} 在 platform-auth 模块
 * 由 {@code REQ20261125230807ResetPasswordEnvelopeIntegrationTest} 钉死；本类钉 system 域 7 处：
 * users 域 DELETE/{userId}、PUT /{userId}/status、PUT /{userId}/roles，tenants 域 PUT /{tenantId}/status、
 * PUT /{tenantId}/quota（AC-20），dict 域 DELETE /dict-types/{typeId}、DELETE /dict-items/{itemId}（AC-20）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li><b>AMB-1（置顶上报，TL 裁决项）</b>：契约确认记录①/API 节/AC-5/AC-20/钉死事实①均写
 *       「9 个 void 端点（9 处）」，但四处对 path 的<b>枚举一致为 8 处</b>（frontmatter apis 9 条中
 *       1 条是新增 GET user-form-options 非 void）。本类按枚举 8 path 钉死，不猜测第 9 处身份；
 *       若第 9 处确有其端点（如同型 void 的角色启停），由 TL 裁决后补充映射表与用例。</li>
 *   <li>AMB-2 夹具：users/roles/tenants 经既有 wire 端点创建（path 有测试先例）；dict 两表经
 *       JdbcTemplate 直插（列面取自 V1__init_schema.sql，AMB-B5 数据面申报口径）。</li>
 *   <li>AMB-3 信封断言：成功=HTTP 200 + 非空体 + success=true + code="0"；data 形状不钉
 *       （Result.ok(null) 的 data 为 null，不参与断言）。</li>
 * </ul>
 *
 * <p>预期红（实现未落地，钉死事实⑨）：现 wire=HTTP 200 + 0 字节体，本类信封断言失败=实现缺口锚点；
 * 路由 404 / Spring 上下文失败 / 夹具 SQL 失败才属用例缺陷需自修。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=Req230807Delta3SystemTestHmacSecret48Bfix000pad0"
})
class REQ20261125230807VoidEndpointsEnvelopeIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String USERS_URL = "/admin/v1/system/users";
    private static final String ROLES_URL = "/admin/v1/system/roles";
    private static final String TENANTS_URL = "/admin/v1/system/tenants";
    private static final String DICT_TYPES_URL = "/admin/v1/system/dict-types";
    private static final String DICT_ITEMS_URL = "/admin/v1/system/dict-items";

    /** 夹具前缀（teardown 按前缀物理清理；与开发侧 *IntegrationTest 前缀隔离防撞名）。 */
    private static final String FIXTURE_PREFIX = "req230807";

    /** dict 夹具 ID 区间（远离种子与雪花撞车区间；teardown 按区间清理）。 */
    private static final long DICT_ID_BASE = 92308070000L;
    private static final long DICT_ID_TOP = 92308079999L;

    private static final String INSERT_DICT_TYPE = """
            insert into sys_dict_type (id, tenant_id, dict_code, dict_name, status, remark,
                                       created_at, created_by, updated_at, updated_by, version, deleted)
            values (?, 'default', ?, ?, 'ENABLED', null, now(), 'req230807-it', now(), 'req230807-it', 0, false)
            """;

    private static final String INSERT_DICT_ITEM = """
            insert into sys_dict_item (id, tenant_id, dict_type_id, item_label, item_value, sort_order, status,
                                       created_at, created_by, updated_at, updated_by, version, deleted)
            values (?, 'default', ?, ?, ?, 0, 'ENABLED', now(), 'req230807-it', now(), 'req230807-it', 0, false)
            """;

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

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        // 真实鉴权过滤器 + 真实 MVC 拦截器链（权限判定）——DeptApi/Tester 先例口径
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：按前缀/ID 区间物理清理（先旁表后主表），并清上下文线程残留
        jdbcTemplate.update("delete from sys_tenant_quota where tenant_id in "
                + "(select id from sys_tenant where tenant_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user_role where tenant_id = 'default' and user_id in "
                + "(select id from sys_user where username like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user where tenant_id = 'default' and username like ?",
                FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_menu where tenant_id = 'default' and role_id in "
                + "(select id from sys_role where role_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_permission where tenant_id = 'default' and role_id in "
                + "(select id from sys_role where role_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role where tenant_id = 'default' and role_code like ?",
                FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_dict_item where id between ? and ?", DICT_ID_BASE, DICT_ID_TOP);
        jdbcTemplate.update("delete from sys_dict_type where id between ? and ?", DICT_ID_BASE, DICT_ID_TOP);
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-5（users 域 3 处） ----------

    @Test
    void should_return_result_envelope_when_user_deleted() throws Exception {
        long userId = createFixtureUser();
        // wire 变更面：DELETE /admin/v1/system/users/{userId} 成功 → HTTP 200 + Result 信封 code="0"
        assertSuccessEnvelope(performAsSuperAdmin(delete(USERS_URL + "/" + userId)), "删除用户");
    }

    @Test
    void should_return_result_envelope_when_user_status_updated() throws Exception {
        long userId = createFixtureUser();
        ObjectNode body = JSON.createObjectNode();
        body.put("status", "DISABLED");
        assertSuccessEnvelope(performAsSuperAdmin(put(USERS_URL + "/" + userId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))),
                "用户启停");
    }

    @Test
    void should_return_result_envelope_when_user_roles_bound() throws Exception {
        long userId = createFixtureUser();
        long roleId = createFixtureRole();
        ObjectNode body = JSON.createObjectNode();
        body.putArray("roleIds").add(String.valueOf(roleId));
        assertSuccessEnvelope(performAsSuperAdmin(put(USERS_URL + "/" + userId + "/roles")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))),
                "绑定角色");
    }

    // ---------- AC-20（tenants 域 2 处） ----------

    @Test
    void should_return_result_envelope_when_tenant_status_updated() throws Exception {
        long tenantId = createFixtureTenant();
        ObjectNode body = JSON.createObjectNode();
        body.put("status", "DISABLED");
        assertSuccessEnvelope(performAsSuperAdmin(put(TENANTS_URL + "/" + tenantId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))),
                "租户启停");
    }

    @Test
    void should_return_result_envelope_when_tenant_quota_saved() throws Exception {
        long tenantId = createFixtureTenant();
        ObjectNode body = JSON.createObjectNode();
        body.put("quotaStorageBytes", 104857600L);
        assertSuccessEnvelope(performAsSuperAdmin(put(TENANTS_URL + "/" + tenantId + "/quota")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))),
                "租户配额保存");
    }

    // ---------- AC-20（dict 域 2 处） ----------

    @Test
    void should_return_result_envelope_when_dict_type_deleted() throws Exception {
        long typeId = nextDictId();
        jdbcTemplate.update(INSERT_DICT_TYPE, typeId, FIXTURE_PREFIX + "-type-del-" + SEQ.incrementAndGet(),
                FIXTURE_PREFIX + " 待删类型");
        // 无条目类型删除成功路径（有条目拒绝属既有守卫，不在本契约面）
        assertSuccessEnvelope(performAsSuperAdmin(delete(DICT_TYPES_URL + "/" + typeId)), "删除字典类型");
    }

    @Test
    void should_return_result_envelope_when_dict_item_deleted() throws Exception {
        long typeId = nextDictId();
        jdbcTemplate.update(INSERT_DICT_TYPE, typeId, FIXTURE_PREFIX + "-type-item-del-" + SEQ.incrementAndGet(),
                FIXTURE_PREFIX + " 条目宿主类型");
        long itemId = nextDictId();
        jdbcTemplate.update(INSERT_DICT_ITEM, itemId, typeId, FIXTURE_PREFIX + " 待删条目",
                "val-" + SEQ.incrementAndGet());
        assertSuccessEnvelope(performAsSuperAdmin(delete(DICT_ITEMS_URL + "/" + itemId)), "删除字典条目");
    }

    // ---------- 夹具装配（既有 wire 端点，成功信封先例口径） ----------

    private long createFixtureUser() throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("username", FIXTURE_PREFIX + "-u-" + SEQ.incrementAndGet());
        body.put("nickname", "信封化夹具用户");
        body.putArray("roleIds");
        JsonNode result = callAsSuperAdmin(post(USERS_URL)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean())
                .as("前置：创建夹具用户应成功（既有端点回归锚）：%s", result).isTrue();
        return result.path("data").path("id").asLong();
    }

    private long createFixtureRole() throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("roleCode", FIXTURE_PREFIX + "-r-" + SEQ.incrementAndGet());
        body.put("roleName", "信封化夹具角色");
        JsonNode result = callAsSuperAdmin(post(ROLES_URL)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean())
                .as("前置：创建夹具角色应成功（既有端点回归锚）：%s", result).isTrue();
        return result.path("data").path("id").asLong();
    }

    private long createFixtureTenant() throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", FIXTURE_PREFIX + "-t-" + SEQ.incrementAndGet());
        body.put("tenantName", "信封化夹具租户");
        JsonNode result = callAsSuperAdmin(post(TENANTS_URL)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean())
                .as("前置：创建夹具租户应成功（既有端点回归锚）：%s", result).isTrue();
        return result.path("data").path("id").asLong();
    }

    private long nextDictId() {
        return DICT_ID_BASE + SEQ.incrementAndGet();
    }

    // ---------- 断言与 HTTP 支撑（Tester 先例口径） ----------

    /** 成功 wire 断言：HTTP 200 + 非空体 + success=true + code="0"（AMB-3；空体=信封化未落地预期红锚点）。 */
    private JsonNode assertSuccessEnvelope(MvcResult result, String what) throws IOException {
        assertThat(result.getResponse().getStatus()).as(what + " 应 HTTP 200").isEqualTo(200);
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).as(what + " 成功 wire=Result 信封（空体=信封化未落地，预期红锚点）").isNotBlank();
        JsonNode body = JSON.readTree(content);
        assertThat(body.path("success").asBoolean()).as(what + " success=true").isTrue();
        assertThat(body.path("code").asText()).as(what + " 成功信封 code=\"0\"").isEqualTo("0");
        return body;
    }

    private void assertRejected(JsonNode body, String what) {
        assertThat(body.path("success").asBoolean()).as(what + " 应被拒绝：%s", body).isFalse();
        assertThat(body.path("code").asText())
                .as(what + " 业务错误=统一响应体 Result.code≠0（勿断言 4xx）").isNotEqualTo("0");
    }

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = performAsSuperAdmin(request);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult performAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }
}
