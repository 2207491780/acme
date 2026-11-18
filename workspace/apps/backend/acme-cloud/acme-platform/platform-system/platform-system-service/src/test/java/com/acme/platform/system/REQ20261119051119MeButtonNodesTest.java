package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
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
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261119-051119 /me 按钮节点面集成测试（Testcontainers PG + MockMvc，装配口径照抄
 * {@code REQ20261117025200SwitchSessionMeIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖 <b>AC-3</b>（契约回填锚点）：超管 /me 菜单树含 BUTTON 节点（menuType=BUTTON，
 * 后端不过滤——前端 nav 构建自行剔除，确认⑤）且 permissions 含对应码（每个 BUTTON 节点的
 * permissionCode ∈ permissions，超管持全量 68 权限点）；普通角色未绑定 BUTTON 时菜单树不含
 * BUTTON 节点、permissions 不含按钮码。</p>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>AC-3 尾句「permissions 集合随 bindMenus 联动变化」的动态差量行为（新增/删除、非按钮来源
 *       不动）由 backend 的 {@code REQ20261119051119ButtonMenuBindIntegrationTest} 覆盖，
 *       本类只覆盖 /me 投影的静态两端（绑定即出现、未绑定即不含），不重复 bindMenus 断言。</li>
 *   <li>普通角色负例直接落 sys_role / sys_role_menu / sys_user_role 行（jdbcTemplate 直插先例：
 *       TenantIsolationIntegrationTest seedUser；无 sys_tenant 种子行、令牌 claims 携带角色），
 *       不经 PUT menus 端点——避免与 AC-2 被测行为耦合。绑定种子 MENU 节点 id=11（V19 异常日志）
 *       使菜单树非空，排除「整体为空」的假阴性。</li>
 * </ul>
 *
 * <p>签名假设见契约回填 AMB 清单（AMB-1/4/5/9/10）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req051119-me-secret-0123456789abcde"
})
class REQ20261119051119MeButtonNodesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 既有 API 权限点（契约背景：68 个操作级粒度码示例；契约确认①：users 创建按钮节点挂此码）。 */
    private static final String USERS_CREATE_CODE = "system:user:create";

    /** V19 种子 MENU 节点（异常日志，id=11，MENU 类型）——负例角色的绑定目标。 */
    private static final long SEED_MENU_LOG_ID = 11L;

    /** 负例租户与固定 id（避开种子雪花 id 区间；唯一键不冲突即可，TenantIsolation 直插先例）。 */
    private static final String NO_BUTTON_TENANT = "req051119-btn-none";
    private static final long NO_BUTTON_USER_ID = 9051119001L;
    private static final long NO_BUTTON_ROLE_ID = 9051119002L;
    private static final long NO_BUTTON_ROLE_MENU_ID = 9051119003L;
    private static final long NO_BUTTON_USER_ROLE_ID = 9051119004L;
    private static final String NO_BUTTON_ROLE_CODE = "req051119_btn_none";

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
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown（绑定表物理删 + 租户归属行按测试租户清理）
        jdbcTemplate.update("delete from sys_user_role where tenant_id = ?", NO_BUTTON_TENANT);
        jdbcTemplate.update("delete from sys_role_menu where tenant_id = ?", NO_BUTTON_TENANT);
        jdbcTemplate.update("delete from sys_role_permission where tenant_id = ?", NO_BUTTON_TENANT);
        jdbcTemplate.update("delete from sys_role where tenant_id = ?", NO_BUTTON_TENANT);
        jdbcTemplate.update("delete from sys_user where tenant_id = ?", NO_BUTTON_TENANT);
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-3：超管 /me 菜单树含 BUTTON 节点，permissions 含对应码 ----------

    @Test
    void should_exposeButtonNodesAndCodes_when_superAdminCallsMe() throws Exception {
        JsonNode me = callMe("1", "default", Set.of("super_admin"));

        assertThat(me.path("code").asText()).as("超管 /me 可达").isEqualTo("0");
        JsonNode data = me.path("data");
        assertThat(data.path("permissions").size()).as("超管持全量权限点").isPositive();

        List<JsonNode> nodes = flattenMenus(data);
        List<JsonNode> buttons = nodes.stream()
                .filter(node -> "BUTTON".equals(node.path("menuType").asText("")))
                .toList();
        assertThat(buttons).as("超管 /me 菜单树应含 menuType=BUTTON 节点（后端不剔除，确认⑤）").isNotEmpty();

        Set<String> permissions = collectPermissions(data);
        assertThat(permissions).as("既有 API 权限点在 permissions 中").contains(USERS_CREATE_CODE);

        for (JsonNode button : buttons) {
            String code = button.path("permissionCode").asText("");
            assertThat(code).as("BUTTON 节点必须挂码（menuId=%s）", button.path("id").asText()).isNotBlank();
            assertThat(permissions)
                    .as("BUTTON 节点 %s 的对应权限码 %s 应在 /me permissions 中", button.path("id").asText(), code)
                    .contains(code);
        }
    }

    // ---------- AC-3：普通角色未绑定 BUTTON 时，/me 菜单树不含 BUTTON 节点、permissions 不含按钮码 ----------

    @Test
    void should_hideButtonNodes_when_roleWithoutButtonBindingCallsMe() throws Exception {
        seedRoleWithoutButtonBinding();

        JsonNode me = callMe(String.valueOf(NO_BUTTON_USER_ID), NO_BUTTON_TENANT, Set.of(NO_BUTTON_ROLE_CODE));

        assertThat(me.path("code").asText()).as("普通角色 /me 可达").isEqualTo("0");
        JsonNode data = me.path("data");

        List<JsonNode> nodes = flattenMenus(data);
        assertThat(nodes).as("绑定了种子 MENU(11) 的角色菜单树非空（排除整体为空的假阴性）").isNotEmpty();
        assertThat(nodes)
                .anyMatch(node -> String.valueOf(SEED_MENU_LOG_ID).equals(node.path("id").asText())
                        && "MENU".equals(node.path("menuType").asText("")))
                .as("绑定的 MENU 节点照常出现（过滤仅针对未绑定的 BUTTON）");

        assertThat(nodes.stream().noneMatch(node -> "BUTTON".equals(node.path("menuType").asText(""))))
                .as("未绑定 BUTTON 的角色菜单树不含 BUTTON 节点").isTrue();

        assertThat(collectPermissions(data))
                .as("无 BUTTON 绑定的角色不应获得按钮权限码").doesNotContain(USERS_CREATE_CODE);
    }

    // ---------- 支撑 ----------

    /** 负例数据：测试租户 + 用户 + 非内置角色 + 仅绑定种子 MENU(11)，不触任何 BUTTON 节点。 */
    private void seedRoleWithoutButtonBinding() {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                                              dept_id, position_id, created_at, created_by, updated_at, updated_by,
                                              version, deleted)
                        values (?, ?, ?, ?, null, null, 'ENABLED', false, null, null,
                                now(), 'req051119-it', now(), 'req051119-it', 0, false)
                        """,
                NO_BUTTON_USER_ID, NO_BUTTON_TENANT, "req051119_btn_user", "req051119_btn_user");
        jdbcTemplate.update("""
                        insert into sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, 'ENABLED', null, false,
                                now(), 'req051119-it', now(), 'req051119-it', 0, false)
                        """,
                NO_BUTTON_ROLE_ID, NO_BUTTON_TENANT, NO_BUTTON_ROLE_CODE, "按钮负例角色");
        jdbcTemplate.update("""
                        insert into sys_role_menu (id, tenant_id, role_id, menu_id,
                                                   created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, now(), 'req051119-it', now(), 'req051119-it', 0, false)
                        """,
                NO_BUTTON_ROLE_MENU_ID, NO_BUTTON_TENANT, NO_BUTTON_ROLE_ID, SEED_MENU_LOG_ID);
        // 兼容 user_role 解析路径（claims 角色为主，直插行兜底，两种实现口径下负例均成立）
        jdbcTemplate.update("""
                        insert into sys_user_role (id, tenant_id, user_id, role_id,
                                                   created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, now(), 'req051119-it', now(), 'req051119-it', 0, false)
                        """,
                NO_BUTTON_USER_ROLE_ID, NO_BUTTON_TENANT, NO_BUTTON_USER_ID, NO_BUTTON_ROLE_ID);
    }

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
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return mvc.perform(request).andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 深度优先拉平 /me 菜单树（menus[].children 递归）。 */
    private static List<JsonNode> flattenMenus(JsonNode data) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode root : data.path("menus")) {
            collect(root, out);
        }
        return out;
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        out.add(node);
        for (JsonNode child : node.path("children")) {
            collect(child, out);
        }
    }

    private static Set<String> collectPermissions(JsonNode data) {
        Set<String> permissions = new HashSet<>();
        data.path("permissions").forEach(permission -> permissions.add(permission.asText()));
        return permissions;
    }
}
