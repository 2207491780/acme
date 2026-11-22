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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261122-102730（BPM-3）V29 流程中心菜单种子迁移集成测试（AC-10；Testcontainers PG，
 * 装配口径照抄 {@code REQ20261122023213SchedulerMenuSeedMigrationTest}（V28 先例）；无 Docker 自动跳过）。
 *
 * <p>契约口径（contracts/REQ-20261122-102730.md 确认③ + AC-10）：</p>
 * <ul>
 *   <li>V29 新增菜单节点：「流程中心」DIRECTORY +「流程定义」（path /workflow，
 *       permission workflow:definition:list=V27 点 78）+「待办中心」（path /workflow/todos，
 *       permission workflow:task:list=V27 点 81）叶 + BUTTON 行×3（deploy 79 / instance:manage 80 /
 *       task:operate 82——V27 五码 78~82 的后三个）对齐 BPM-2 fixture 先例形状；</li>
 *   <li><b>无新权限点</b>（坑 16 计数不变）：workflow:% 域权限点恰为 V27 五码，水位不变；
 *       所有菜单行挂既有 API 权限点；</li>
 *   <li>幂等：固定主键 + ON CONFLICT 先例 → 迁移+重放零行变更（行值含 version 不变 + 计数不变）；</li>
 *   <li>超管 /admin/v1/system/me 动态菜单下发含流程中心树（绑定先例口径）。</li>
 * </ul>
 *
 * <p>计数断言一律<b>相对口径</b>（重放前后不变），不钉 sys_menu/sys_permission 绝对水位
 * （pitfall 16：跨 REQ 基线计数漂移）；V29 脚本经 {@code classpath*:db/migration/system/V29__*.sql}
 * 模式发现并重放（分号切分、剔除 -- 注释行，照 V28 先例）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req102730-process-menu-secret-0123456789"
})
class REQ20261122102730ProcessCenterMenuSeedMigrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** V27 五码（wire 字符串唯一事实源：V27__workflow_permission.sql）。 */
    private static final String DEF_LIST = "workflow:definition:list";
    private static final String DEF_DEPLOY = "workflow:definition:deploy";
    private static final String INST_MANAGE = "workflow:instance:manage";
    private static final String TASK_LIST = "workflow:task:list";
    private static final String TASK_OPERATE = "workflow:task:operate";
    private static final Set<String> V27_FIVE_CODES =
            Set.of(DEF_LIST, DEF_DEPLOY, INST_MANAGE, TASK_LIST, TASK_OPERATE);

    private static final String DEF_PATH = "/workflow";
    private static final String TODO_PATH = "/workflow/todos";
    private static final String DIR_TITLE = "流程中心";
    private static final String DEF_TITLE = "流程定义";
    private static final String TODO_TITLE = "待办中心";

    /** 超管角色（V2 种子：role_id=1 / tenant 'default'，V28 先例同款）。 */
    private static final String DEFAULT_TENANT = "default";

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
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-10：V29 菜单树形状（DIRECTORY + 两叶 + BUTTON×3，全挂既有 API 权限点） ----------

    @Test
    void should_seedProcessCenterMenuTree_when_flywayAppliedToV29() {
        // ① 流程定义叶：path /workflow + workflow:definition:list
        List<Map<String, Object>> defLeaf = jdbcTemplate.queryForList(
                "select menu_name, permission_code, menu_type, deleted from sys_menu "
                        + "where path = ? and deleted = false", DEF_PATH);
        assertThat(defLeaf).as("/workflow 菜单叶唯一").hasSize(1);
        Map<String, Object> defRow = defLeaf.get(0);
        assertThat(defRow.get("menu_name")).as("流程定义叶标题").isEqualTo(DEF_TITLE);
        assertThat(defRow.get("permission_code")).as("流程定义叶挂 V27 点 78").isEqualTo(DEF_LIST);
        assertThat(defRow.get("menu_type")).as("流程定义为叶子节点").isEqualTo("MENU");

        // ② 待办中心叶：path /workflow/todos + workflow:task:list（独立路由页入口）
        List<Map<String, Object>> todoLeaf = jdbcTemplate.queryForList(
                "select menu_name, permission_code, menu_type, deleted from sys_menu "
                        + "where path = ? and deleted = false", TODO_PATH);
        assertThat(todoLeaf).as("/workflow/todos 菜单叶唯一").hasSize(1);
        Map<String, Object> todoRow = todoLeaf.get(0);
        assertThat(todoRow.get("menu_name")).as("待办中心叶标题").isEqualTo(TODO_TITLE);
        assertThat(todoRow.get("permission_code")).as("待办中心叶挂 V27 点 81").isEqualTo(TASK_LIST);
        assertThat(todoRow.get("menu_type")).as("待办中心为叶子节点").isEqualTo("MENU");

        // ③ 流程中心 DIRECTORY 节点
        long dirCount = count("select count(*) from sys_menu where menu_name = '" + DIR_TITLE
                + "' and menu_type = 'DIRECTORY' and deleted = false");
        assertThat(dirCount).as("流程中心 DIRECTORY 唯一").isEqualTo(1L);

        // ④ BUTTON 行×3（deploy/instance:manage/task:operate）；V27 前无菜单、V29 后恰 3 行 BUTTON
        List<String> buttonCodes = jdbcTemplate.queryForList(
                "select permission_code from sys_menu where menu_type = 'BUTTON' "
                        + "and permission_code in (?, ?, ?) and deleted = false",
                String.class, DEF_DEPLOY, INST_MANAGE, TASK_OPERATE);
        assertThat(buttonCodes).as("V29 BUTTON 行恰 3 条且覆盖 79/80/82 三码")
                .containsExactlyInAnyOrder(DEF_DEPLOY, INST_MANAGE, TASK_OPERATE);

        // ⑤ V29 子树全部菜单行挂既有 API 权限点（存在、API 类型、启用、未删——V28 先例 join 口径）
        assertThat(count("""
                select count(*) from sys_menu m
                join sys_permission p on p.permission_code = m.permission_code
                where m.permission_code in ('%s', '%s', '%s', '%s', '%s')
                  and p.permission_type = 'API' and p.status = 'ENABLED' and p.deleted = false
                """.formatted(DEF_LIST, DEF_DEPLOY, INST_MANAGE, TASK_LIST, TASK_OPERATE)))
                .as("V29 菜单引用的权限码均为既有 API 权限点（5 行）").isEqualTo(5L);

        // ⑥ 无新权限点（AC-10 坑 16 计数不变）：workflow:% 域恰为 V27 五码，不因 V29 增行
        assertThat(count("select count(*) from sys_permission where permission_code like 'workflow:%'"))
                .as("workflow:% 权限点恰为 V27 五码（V29 无新权限点）").isEqualTo(5L);
    }

    // ---------- AC-10：V29 重放幂等（零行变更：行值含 version 完全不变，计数不漂移） ----------

    @Test
    void should_keepMenuCountsAndRowValuesStable_when_v29StatementsReplayed() {
        Map<String, Object> defBefore = jdbcTemplate.queryForMap(
                "select menu_name, permission_code, version from sys_menu where path = ?", DEF_PATH);
        Map<String, Object> todoBefore = jdbcTemplate.queryForMap(
                "select menu_name, permission_code, version from sys_menu where path = ?", TODO_PATH);
        long menuTotalBefore = count("select count(*) from sys_menu");
        long permissionTotalBefore = count("select count(*) from sys_permission");
        long buttonTotalBefore = count("select count(*) from sys_menu where menu_type = 'BUTTON'");
        long bindingTotalBefore = count("select count(*) from sys_role_menu");

        replayV29();

        Map<String, Object> defAfter = jdbcTemplate.queryForMap(
                "select menu_name, permission_code, version from sys_menu where path = ?", DEF_PATH);
        Map<String, Object> todoAfter = jdbcTemplate.queryForMap(
                "select menu_name, permission_code, version from sys_menu where path = ?", TODO_PATH);
        assertThat(defAfter).as("重放后 /workflow 叶行值含 version 完全不变").isEqualTo(defBefore);
        assertThat(todoAfter).as("重放后 /workflow/todos 叶行值含 version 完全不变").isEqualTo(todoBefore);
        assertThat(count("select count(*) from sys_menu")).as("重放后 sys_menu 总数不变").isEqualTo(menuTotalBefore);
        assertThat(count("select count(*) from sys_permission")).as("重放后 sys_permission 总数不变").isEqualTo(permissionTotalBefore);
        assertThat(count("select count(*) from sys_menu where menu_type = 'BUTTON'")).as("重放后 BUTTON 总数不变").isEqualTo(buttonTotalBefore);
        assertThat(count("select count(*) from sys_role_menu")).as("重放后超管绑定行总数不变").isEqualTo(bindingTotalBefore);
        assertThat(count("select count(*) from sys_menu where path = '" + DEF_PATH + "' and deleted = false"))
                .as("重放后 /workflow 叶不重复").isEqualTo(1L);
        assertThat(count("select count(*) from sys_menu where path = '" + TODO_PATH + "' and deleted = false"))
                .as("重放后 /workflow/todos 叶不重复").isEqualTo(1L);
    }

    // ---------- AC-10：me.menus 动态下发含流程中心树 ----------

    @Test
    void should_exposeProcessCenterMenusViaMe_when_superAdminCallsMe() throws Exception {
        MvcResult result = mvc.perform(get("/admin/v1/system/me")
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", DEFAULT_TENANT, Channel.B, Set.of("super_admin"))))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("超管 /me 可达").isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).as("超管 /me 业务成功信封").isEqualTo("0");
        JsonNode data = body.path("data");

        List<JsonNode> nodes = flattenMenus(data);
        List<JsonNode> defNodes = nodes.stream()
                .filter(node -> DEF_PATH.equals(node.path("path").asText("")))
                .toList();
        assertThat(defNodes).as("me.menus 含 /workflow 菜单叶且唯一").hasSize(1);
        JsonNode defNode = defNodes.get(0);
        assertThat(defNode.path("permissionCode").asText(""))
                .as("下发流程定义叶权限点=workflow:definition:list").isEqualTo(DEF_LIST);
        assertThat(menuTitle(defNode)).as("下发流程定义叶标题=流程定义").isEqualTo(DEF_TITLE);

        List<JsonNode> todoNodes = nodes.stream()
                .filter(node -> TODO_PATH.equals(node.path("path").asText("")))
                .toList();
        assertThat(todoNodes).as("me.menus 含 /workflow/todos 菜单叶且唯一").hasSize(1);
        JsonNode todoNode = todoNodes.get(0);
        assertThat(todoNode.path("permissionCode").asText(""))
                .as("下发待办中心叶权限点=workflow:task:list").isEqualTo(TASK_LIST);
        assertThat(menuTitle(todoNode)).as("下发待办中心叶标题=待办中心").isEqualTo(TODO_TITLE);

        long dirNodes = nodes.stream()
                .filter(node -> DIR_TITLE.equals(menuTitle(node)))
                .count();
        assertThat(dirNodes).as("me.menus 含流程中心 DIRECTORY 节点").isGreaterThanOrEqualTo(1);

        assertThat(collectPermissions(data))
                .as("超管 permissions 含 V27 点 78/81（全量口径）")
                .contains(DEF_LIST, TASK_LIST);
    }

    // ---------- 支撑 ----------

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        assertThat(value).as("计数查询必须返回标量：%s", sql).isNotNull();
        return value;
    }

    /** me.menus 节点标题宽容解析：menuName/name wire 口径任一（V28 先例）。 */
    private static String menuTitle(JsonNode node) {
        return node.path("menuName").asText(node.path("name").asText(""));
    }

    /** 按模式发现 V29 迁移脚本（不钉死文件名后缀），逐条重放非注释语句（分号切分，V28 先例）。 */
    private void replayV29() {
        try {
            Resource[] scripts = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/system/V29__*.sql");
            assertThat(scripts).as("V29 迁移脚本须存在于 platform-system").isNotEmpty();
            for (Resource script : scripts) {
                for (String chunk : readResource(script).split(";")) {
                    String statement = chunk.lines()
                            .filter(line -> !line.strip().startsWith("--"))
                            .collect(Collectors.joining("\n"))
                            .strip();
                    if (!statement.isEmpty()) {
                        jdbcTemplate.execute(statement);
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取 V29 迁移脚本失败", ex);
        }
    }

    private static String readResource(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 深度优先拉平 /me 菜单树（menus[].children 递归，V28 先例口径）。 */
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
        Set<String> permissions = new java.util.HashSet<>();
        data.path("permissions").forEach(permission -> permissions.add(permission.asText()));
        return permissions;
    }
}
