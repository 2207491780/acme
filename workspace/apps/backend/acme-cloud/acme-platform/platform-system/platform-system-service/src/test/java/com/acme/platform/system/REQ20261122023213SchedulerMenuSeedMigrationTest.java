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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261122-023213（JOB-3）V28 调度中心菜单种子迁移集成测试（Testcontainers PG，装配口径照抄
 * {@code REQ20261119051119ButtonMenuMigrationTest} / {@code REQ20261119051119MeButtonNodesTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖 <b>AC-11</b>：V28 将 /jobs 菜单叶（id=12，V19 种子，原「定时任务」/infra:job:list）
 * 更新为「调度中心」/scheduler:job:list（V26 权限点 73），path/component/icon 等其余列不动、
 * 不新增权限点与绑定行；迁移+重放幂等（V28 为条件 UPDATE + 旧值守卫，重放零行变更）；
 * 超管 /admin/v1/system/me 动态菜单下发含更新后的 /jobs 菜单叶（标题与权限点均为新值）。</p>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>计数断言一律<b>相对口径</b>（重放前后不变），不钉 sys_menu/sys_permission 绝对水位
 *       （pitfall 16：跨 REQ 基线计数漂移）；V28 本身不新增行（UPDATE 语义）。</li>
 *   <li>幂等 oracle = 重放后行值含 {@code version} 完全不变（V28 仅在守卫命中时
 *       {@code version = version + 1}，零行变更即 version 不动）。</li>
 *   <li>V28 脚本文件名经 {@code classpath*:db/migration/system/V28__*.sql} 模式发现，
 *       不钉死具体后缀；重放按分号切分、剔除 -- 注释行后逐条执行（照 ButtonMenuMigrationTest 口径）。</li>
 *   <li>me.menus 菜单叶标题 wire 字段名未在契约钉死（menuName/name 两口径任一命中即过）；
 *       path/permissionCode wire 字段名沿用 {@code REQ20261119051119MeButtonNodesTest} 先例。</li>
 * </ul>
 */
class REQ20261122023213SchedulerMenuSeedMigrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** /jobs 菜单叶（V19 种子 id=12，V28 更新对象；path/component 不动）。 */
    private static final long JOBS_MENU_ID = 12L;
    private static final String JOBS_PATH = "/jobs";
    private static final String NEW_TITLE = "调度中心";
    private static final String NEW_PERMISSION = "scheduler:job:list";
    private static final String SEED_COMPONENT = "infra/jobs/index";

    /** 超管角色（V2 种子：role_id=1 / tenant 'default'，绑定先例同 ButtonMenuMigrationTest）。 */
    private static final long SUPER_ADMIN_ROLE_ID = 1L;
    private static final String DEFAULT_TENANT = "default";

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

    // ---------- AC-11：V28 应用后 /jobs 菜单叶标题与权限点更新，其余列不动、不新增行 ----------

    @Test
    void should_updateJobsMenuTitleAndPermission_when_flywayAppliedToV28() {
        assertThat(count("select count(*) from sys_menu where path = '/jobs' and deleted = false"))
                .as("/jobs 菜单叶唯一（复用 id=12，V28 不新增行）").isEqualTo(1L);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select menu_name, permission_code, menu_type, component, deleted from sys_menu where id = ?", JOBS_MENU_ID);
        assertThat(row.get("menu_name")).as("菜单叶标题更新为调度中心").isEqualTo(NEW_TITLE);
        assertThat(row.get("permission_code")).as("菜单叶权限点换绑 scheduler:job:list（V26 点 73）").isEqualTo(NEW_PERMISSION);
        assertThat(row.get("menu_type")).as("仍为 MENU 叶子类型").isEqualTo("MENU");
        assertThat(row.get("component")).as("component 不动（V28 只改 menu_name/permission_code）").isEqualTo(SEED_COMPONENT);
        assertThat(row.get("deleted")).as("未删除").isEqualTo(false);

        // 换绑后的权限点必须是既有 API 权限点（存在、API 类型、启用、未删——V28 不新建权限点）
        assertThat(count("""
                select count(*) from sys_menu m
                join sys_permission p on p.permission_code = m.permission_code
                where m.id = %d and p.permission_type = 'API' and p.status = 'ENABLED' and p.deleted = false
                """.formatted(JOBS_MENU_ID)))
                .as("menu_name/permission_code 挂既有 API 权限点 scheduler:job:list").isEqualTo(1L);

        // 超管绑定仍在（me.menus 下发前提；V28 不动 role_menu）
        assertThat(superAdminJobsBindingCount())
                .as("超管 role_menu 仍绑定 /jobs 菜单叶").isEqualTo(1L);
    }

    // ---------- AC-11：V28 重放幂等（零行变更：行值含 version 完全不变，计数不漂移） ----------

    @Test
    void should_keepRowValueAndCountsStable_when_v28StatementsReplayed() {
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "select menu_name, permission_code, version from sys_menu where id = ?", JOBS_MENU_ID);
        long menuTotalBefore = count("select count(*) from sys_menu");
        long permissionTotalBefore = count("select count(*) from sys_permission");
        long bindingBefore = superAdminJobsBindingCount();

        replayV28();

        Map<String, Object> after = jdbcTemplate.queryForMap(
                "select menu_name, permission_code, version from sys_menu where id = ?", JOBS_MENU_ID);
        assertThat(after.get("menu_name")).as("重放后标题仍为调度中心").isEqualTo(before.get("menu_name")).isEqualTo(NEW_TITLE);
        assertThat(after.get("permission_code")).as("重放后权限点仍为 scheduler:job:list")
                .isEqualTo(before.get("permission_code")).isEqualTo(NEW_PERMISSION);
        assertThat(after.get("version"))
                .as("重放后 version 不变（守卫零行变更=重放幂等的强 oracle）").isEqualTo(before.get("version"));
        assertThat(count("select count(*) from sys_menu where path = '/jobs' and deleted = false"))
                .as("重放后 /jobs 菜单叶不重复").isEqualTo(1L);
        assertThat(count("select count(*) from sys_menu")).as("重放后 sys_menu 总数不变").isEqualTo(menuTotalBefore);
        assertThat(count("select count(*) from sys_permission")).as("重放后 sys_permission 总数不变").isEqualTo(permissionTotalBefore);
        assertThat(superAdminJobsBindingCount()).as("重放后超管绑定行不变").isEqualTo(bindingBefore);
    }

    // ---------- AC-11：me.menus 动态下发含更新后的 /jobs 菜单叶 ----------

    @Test
    void should_exposeUpdatedJobsMenuViaMeMenus_when_superAdminCallsMe() throws Exception {
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
        List<JsonNode> jobsNodes = nodes.stream()
                .filter(node -> JOBS_PATH.equals(node.path("path").asText("")))
                .toList();
        assertThat(jobsNodes).as("me.menus 含 /jobs 菜单叶且唯一").hasSize(1);

        JsonNode jobs = jobsNodes.get(0);
        assertThat(jobs.path("permissionCode").asText(""))
                .as("下发菜单叶权限点=更新后的 scheduler:job:list").isEqualTo(NEW_PERMISSION);
        String title = jobs.path("menuName").asText(jobs.path("name").asText(""));
        assertThat(title).as("下发菜单叶标题=更新后的调度中心（menuName/name wire 口径任一）").isEqualTo(NEW_TITLE);

        assertThat(collectPermissions(data))
                .as("permissions 含 scheduler:job:list（超管全量口径）").contains(NEW_PERMISSION);
        // /jobs 叶不再挂旧权限点下发（旧 infra:job:list 权限点行保留，但菜单叶已换绑）
        assertThat(jobsNodes.stream().noneMatch(node -> "infra:job:list".equals(node.path("permissionCode").asText(""))))
                .as("/jobs 菜单叶不再以 infra:job:list 下发").isTrue();
    }

    // ---------- 支撑 ----------

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        assertThat(value).as("计数查询必须返回标量：%s", sql).isNotNull();
        return value;
    }

    private long superAdminJobsBindingCount() {
        return count("""
                select count(*) from sys_role_menu rm
                where rm.menu_id = (select id from sys_menu where path = '%s' and deleted = false)
                  and rm.role_id = %d and rm.tenant_id = '%s'
                """.formatted(JOBS_PATH, SUPER_ADMIN_ROLE_ID, DEFAULT_TENANT));
    }

    /** 按模式发现 V28 迁移脚本（不钉死文件名后缀），逐条重放非注释语句（分号切分，ButtonMenuMigrationTest 口径）。 */
    private void replayV28() {
        try {
            Resource[] scripts = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/system/V28__*.sql");
            assertThat(scripts).as("V28 迁移脚本须存在于 platform-system").isNotEmpty();
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
            throw new IllegalStateException("读取 V28 迁移脚本失败", ex);
        }
    }

    private static String readResource(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 深度优先拉平 /me 菜单树（menus[].children 递归，MeButtonNodesTest 口径）。 */
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
