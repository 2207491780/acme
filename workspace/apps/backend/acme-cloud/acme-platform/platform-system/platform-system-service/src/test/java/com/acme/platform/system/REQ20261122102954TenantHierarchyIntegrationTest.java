package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.infrastructure.client.TenantSessionRevocationGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-102954 子租户层级（TENANT-3）platform-system 服务域集成测试（Testcontainers PG，
 * 装配口径照 {@code REQ20261121022910TenantPackageApiIntegrationTest}/{@code SystemIntegrationTest}；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-1~8/11（§确认记录②~⑨、§验收标准）：</p>
 * <ul>
 *   <li>AC-1：V30 迁移列就绪（parent_id 可空 / level NOT NULL DEFAULT 1）+ idx(parent_id)
 *       + 存量（default 种子）level=1 回填 + 脚本语句重放幂等。</li>
 *   <li>AC-2：init 带 parentId → 子租户落库 parent_id/level=父+1 + 初始管理员归子租户编码
 *       （sys_user.tenant_id 存编码——钉死事实）+ 子租户含角色。</li>
 *   <li>AC-3：parent 链深度已达 3 → 再建子级/改父越限均 TENANT_DEPTH_EXCEEDED 拒绝且不落行。</li>
 *   <li>AC-4：改父构成环（A→B→A / 自引用）→ TENANT_CYCLE 拒绝且原数据不动；合法改父
 *       （后代整体上移）放行并重算 level。</li>
 *   <li>AC-5：停用父租户 → 后代全部级联停用 + 逐租户撤会话（撤会话网关交互断言，AMB-3）；
 *       恢复父 → 子孙保持停用（显式恢复），恢复不触发撤销。</li>
 *   <li>AC-6：GET /tenants/tree → 全量树形（节点含 status/level/parentId/套餐），平台侧可见。</li>
 *   <li>AC-7：父租户上下文访问业务数据 → 子租户数据不可见（零旁路，既有 TenantContext
 *       隔离口径回归断言，REQ20261114120104TenantIsolationIntegrationTest 同款断言面）。</li>
 *   <li>AC-8：子租户独立套餐绑定 → 父租户变更/解除套餐不影响子 package_id。</li>
 *   <li>AC-11：既有租户域回归——default 租户不可停用（TENANT_DISABLE_DENIED）、
 *       简单 create 缺省 parentId 落顶层、list 下发层级字段。</li>
 * </ul>
 *
 * <p>AC-12（子租户用量独立计量）为契约口径记录（确认⑥：TENANT-4 统一配额框架实现依据），
 * 本 REQ 无可观测行为面，不设虚假用例；子租户资源独立性由 AC-8（套餐独立）侧面佐证。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 parentId wire=数字（AdminTenantCreate.parentId Long，packageId 数字 wire 先例；
 *       id wire 字符串化雪花，读侧经 asLong 归一）。</li>
 *   <li>AMB-2 V30 重放=脚本语句原样重放（AC-1 钉死「执行+重放…幂等」→ 语句须可重复执行：
 *       IF NOT EXISTS / 守卫式 UPDATE；若实现以「Flyway 一次性执行即幂等」为口径，请 TL 裁决对齐）。</li>
 *   <li>AMB-3 级联撤会话断言=TenantSessionRevocationGateway 交互（@MockitoBean 桩，
 *       RoleAndTenantUseCaseTest 先例）；auth_session 底账 KICKED/Redis 撤销键归 platform-auth 域
 *       （REQ20261117025200TenantSessionRevokeIntegrationTest 已覆盖），本类不跨域重复。</li>
 *   <li>AMB-4 tree 节点形状宽容：叶节点 children 可缺省/空数组；根数组可裸数组或 records 包裹。</li>
 *   <li>依赖（映射表注明，backend 工人并行交付）：V30__ 迁移、SystemErrorCodes 两枚新码
 *       （TENANT_DEPTH_EXCEEDED/TENANT_CYCLE，归租户域段）、create/init/update 收 parentId、
 *       list/tree 层级字段。实现未落盘时本类编译失败/断言红灯 = 疑点非用例缺陷
 *       （REQ-022910 先例口径，附原样输出归因 TL）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-tenant-hier-it-secret-0123456"
})
class REQ20261122102954TenantHierarchyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req102954-it";

    private static final String LIST_URL = "/admin/v1/system/tenants";

    /** 夹具租户码前缀（teardown 按前缀物理清理；唯一性靠每用例独立前缀）。 */
    private static final String FIXTURE_PREFIX = "req102954";

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

    /** 撤会话网关桩（AMB-3）：停用级联的逐租户撤会话以交互断言承载，不跨域探 auth 存储。 */
    @MockitoBean
    private TenantSessionRevocationGateway revocationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 夹具脏数据 teardown：按夹具前缀物理清理（init 建出的角色/用户/绑定随租户码清除）
        jdbcTemplate.update("delete from sys_user_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_menu where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_package_menu");
        jdbcTemplate.update("delete from sys_tenant_package");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：V30 迁移列就绪 + 存量回填 + 重放幂等 ----------

    @Test
    void should_apply_v30_columns_and_backfill_and_be_idempotent_when_migration_executed_and_replayed()
            throws Exception {
        // ① 迁移脚本存在（backend 并行交付锚点）
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:db/migration/system/V30__*.sql");
        assertThat(resources).as("V30 迁移脚本缺失（backend 未落盘或命名不符 V30__*.sql）").isNotEmpty();

        // ② 列定义就绪（契约确认②钉死：parent_id bigint NULL=顶层；level int NOT NULL DEFAULT 1）
        Map<String, Object> parentColumn = jdbcTemplate.queryForMap(
                "select data_type, is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant' and column_name='parent_id'");
        assertThat(String.valueOf(parentColumn.get("data_type"))).as("parent_id 应为 bigint").contains("bigint");
        assertThat(parentColumn.get("is_nullable")).as("parent_id NULL=顶层（可空）").isEqualTo("YES");
        Map<String, Object> levelColumn = jdbcTemplate.queryForMap(
                "select data_type, is_nullable, column_default from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant' and column_name='level'");
        assertThat(String.valueOf(levelColumn.get("data_type"))).as("level 应为 int/integer")
                .contains("int");
        assertThat(levelColumn.get("is_nullable")).as("level NOT NULL（物化层级列）").isEqualTo("NO");
        assertThat(String.valueOf(levelColumn.get("column_default"))).as("level DEFAULT 1").contains("1");

        // ③ 存量回填：迁移前既有租户（default 种子）level=1 且无父
        Map<String, Object> defaultTenant = jdbcTemplate.queryForMap(
                "select level, parent_id from sys_tenant where tenant_code = 'default'");
        assertThat(((Number) defaultTenant.get("level")).intValue()).as("存量回填 level=1").isEqualTo(1);
        assertThat(defaultTenant.get("parent_id")).as("存量租户 parent_id NULL（顶层）").isNull();

        // ④ idx(parent_id) 就绪（契约确认②）
        Integer parentIndexCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname='public' and tablename='sys_tenant' "
                        + "and indexdef like '%parent_id%'",
                Integer.class);
        assertThat(parentIndexCount).as("V30 应建 parent_id 索引").isGreaterThanOrEqualTo(1);

        // ⑤ 重放幂等（AMB-2）：语句原样重放后层级数据零变化
        List<Map<String, Object>> before = hierarchySnapshot();
        replayV30Statements(resources);
        assertThat(hierarchySnapshot()).as("重放后层级数据零变化（幂等）").isEqualTo(before);
    }

    private List<Map<String, Object>> hierarchySnapshot() {
        return jdbcTemplate.queryForList(
                "select tenant_code, parent_id, level from sys_tenant order by tenant_code");
    }

    private void replayV30Statements(org.springframework.core.io.Resource[] resources) throws IOException {
        for (var resource : resources) {
            String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String chunk : script.split(";")) {
                String statement = chunk.lines()
                        .filter(line -> !line.strip().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .strip();
                if (!statement.isEmpty()) {
                    jdbcTemplate.execute(statement);
                }
            }
        }
    }

    // ---------- AC-2：init 带 parentId → 子租户 + 管理员归子租户 ----------

    @Test
    void should_create_child_tenant_and_bind_admin_to_child_when_init_with_parentId() throws Exception {
        // 顶层父（无 parentId）→ level=1 / parent NULL
        String parentCode = initTenant(FIXTURE_PREFIX + "-p", null);
        long parentId = tenantIdByCode(parentCode);
        assertThat(jdbcTemplate.queryForMap("select level, parent_id from sys_tenant where tenant_code = ?",
                parentCode))
                .as("init 缺省 parentId=顶层（level=1/parent NULL）")
                .containsEntry("level", 1)
                .containsEntry("parent_id", null);

        // 子租户（parentId 指向父）→ parent_id 落库 + level=父+1 + 管理员归子租户编码
        JsonNode childResult = initTenantOk(FIXTURE_PREFIX + "-c", parentId);
        assertThat(childResult.path("data").path("tenantCode").asText()).isEqualTo(FIXTURE_PREFIX + "-c");
        Map<String, Object> childRow = jdbcTemplate.queryForMap(
                "select id, level, parent_id from sys_tenant where tenant_code = ?", FIXTURE_PREFIX + "-c");
        assertThat(((Number) childRow.get("parent_id")).longValue()).as("子租户 parent_id=父 id").isEqualTo(parentId);
        assertThat(((Number) childRow.get("level")).intValue()).as("子租户 level=父+1").isEqualTo(2);
        Map<String, Object> childAdmin = jdbcTemplate.queryForMap(
                "select tenant_id from sys_user where username = ?", FIXTURE_PREFIX + "_c_admin");
        assertThat(childAdmin.get("tenant_id")).as("初始管理员归属子租户编码（sys_user.tenant_id 存编码）")
                .isEqualTo(FIXTURE_PREFIX + "-c");
        Integer childRoles = jdbcTemplate.queryForObject(
                "select count(*) from sys_role where tenant_id = ?", Integer.class, FIXTURE_PREFIX + "-c");
        assertThat(childRoles).as("开通模板应在子租户内建 tenant_admin 角色（复用开通链）").isGreaterThanOrEqualTo(1);

        // 第三层 → level=3（深度上限内的合法建链）
        long childId = ((Number) childRow.get("id")).longValue();
        initTenantOk(FIXTURE_PREFIX + "-g", childId);
        Integer grandchildLevel = jdbcTemplate.queryForObject(
                "select level from sys_tenant where tenant_code = ?", Integer.class, FIXTURE_PREFIX + "-g");
        assertThat(grandchildLevel).as("第三层 level=3（上限内合法）").isEqualTo(3);
    }

    // ---------- AC-3：深度超限拒绝（创建 + 改父双路径） ----------

    @Test
    void should_reject_depth_exceeded_when_creating_fourth_level_or_reparenting_beyond_limit() throws Exception {
        long[] chain = seedChain(FIXTURE_PREFIX + "-d");
        long grandchildId = chain[2];

        // 创建路径：parent 链已达 3 层 → 再建子级 TENANT_DEPTH_EXCEEDED，不落行
        JsonNode rejectedCreate = callAsSuperAdmin(post(LIST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(createBody(FIXTURE_PREFIX + "-d-l4", grandchildId))));
        assertThat(rejectedCreate.path("success").asBoolean()).as("深度超限创建应拒绝：%s", rejectedCreate).isFalse();
        assertThat(rejectedCreate.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_DEPTH_EXCEEDED);
        assertThat(tenantIdByCodeOrNull(FIXTURE_PREFIX + "-d-l4"))
                .as("超限创建不得落行").isNull();

        // 改父路径：顶层租户改挂第三层之下 → 同样 TENANT_DEPTH_EXCEEDED，原数据不动
        String topCode = createTenantOk(FIXTURE_PREFIX + "-d-top");
        long topId = tenantIdByCode(topCode);
        JsonNode rejectedUpdate = updateTenant(topId, tenantNameOf(topId), versionOf(topId), grandchildId, null);
        assertThat(rejectedUpdate.path("success").asBoolean()).as("深度超限改父应拒绝：%s", rejectedUpdate).isFalse();
        assertThat(rejectedUpdate.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_DEPTH_EXCEEDED);
        Map<String, Object> topRow = jdbcTemplate.queryForMap(
                "select parent_id, level from sys_tenant where tenant_code = ?", topCode);
        assertThat(topRow.get("parent_id")).as("超限改父原数据不动（仍顶层）").isNull();
        assertThat(((Number) topRow.get("level")).intValue()).as("超限改父 level 不变").isEqualTo(1);
    }

    // ---------- AC-4：改父构成环拒绝且原数据不动；合法改父放行并重算 ----------

    @Test
    void should_reject_cycle_and_keep_original_when_update_parent_forms_cycle() throws Exception {
        // A（顶层）→ B（子）
        long parentA = tenantIdByCode(createTenantOk(FIXTURE_PREFIX + "-a"));
        long childB = tenantIdByCode(createTenantOk(FIXTURE_PREFIX + "-b", parentA));

        // A→B→A 环：A 改父为 B → TENANT_CYCLE，A 原数据不动
        JsonNode cycleByDescendant = updateTenant(parentA, tenantNameOf(parentA), versionOf(parentA), childB, null);
        assertThat(cycleByDescendant.path("success").asBoolean())
                .as("改父构成环应拒绝：%s", cycleByDescendant).isFalse();
        assertThat(cycleByDescendant.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_CYCLE);
        assertThat(jdbcTemplate.queryForObject(
                "select parent_id from sys_tenant where id = ?", Long.class, parentA))
                .as("环拒绝后 A 仍顶层").isNull();

        // 自引用环：B 改父为自身 → TENANT_CYCLE，B 原数据不动
        JsonNode selfCycle = updateTenant(childB, tenantNameOf(childB), versionOf(childB), childB, null);
        assertThat(selfCycle.path("success").asBoolean()).as("自引用改父应拒绝：%s", selfCycle).isFalse();
        assertThat(selfCycle.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_CYCLE);
        assertThat(jdbcTemplate.queryForObject(
                "select parent_id from sys_tenant where id = ?", Long.class, childB))
                .as("自引用拒绝后 B 父不变").isEqualTo(parentA);

        // 合法改父（正例不误伤）：链 P→C→G，G 上移挂到顶层 P → level 3→2 重算
        long[] chain = seedChain(FIXTURE_PREFIX + "-m");
        JsonNode legalMove = updateTenant(chain[2], tenantNameOf(chain[2]), versionOf(chain[2]), chain[0], null);
        assertThat(legalMove.path("success").asBoolean()).as("合法改父应放行：%s", legalMove).isTrue();
        Map<String, Object> movedRow = jdbcTemplate.queryForMap(
                "select parent_id, level from sys_tenant where id = ?", chain[2]);
        assertThat(((Number) movedRow.get("parent_id")).longValue()).as("改父生效").isEqualTo(chain[0]);
        assertThat(((Number) movedRow.get("level")).intValue()).as("后代/自身 level 重算").isEqualTo(2);
    }

    // ---------- AC-5：级联停用 + 逐租户撤会话 + 恢复不自动恢复子 ----------

    @Test
    void should_cascade_disable_descendants_and_revoke_sessions_when_parent_disabled_and_keep_children_suspended_when_restored()
            throws Exception {
        long[] chain = seedChain(FIXTURE_PREFIX + "-cas"); // P→C→G
        String siblingCode = createTenantOk(FIXTURE_PREFIX + "-cas-sib"); // 无关租户（不受级联波及）

        when(revocationGateway.revokeByTenant(anyString()))
                .thenReturn(new TenantSessionRevocationGateway.RevocationOutcome(1, 0));

        // 停用父租户 → 后代全部级联停用
        JsonNode disabled = putStatus(chain[0], "DISABLED");
        assertThat(disabled.path("success").asBoolean()).as("停用父租户应成功：%s", disabled).isTrue();
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-p")).as("父停用").isEqualTo("DISABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-c")).as("子级联停用（沿 parent 链递归）").isEqualTo("DISABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-g")).as("孙级联停用（递归到叶）").isEqualTo("DISABLED");
        assertThat(tenantStatus(siblingCode)).as("无关租户不波及").isEqualTo("ENABLED");

        // 逐租户撤会话（确认⑦：级联停用+逐租户撤会话；AMB-3 网关交互口径）
        List<String> revokedTenants = new ArrayList<>();
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(revocationGateway, times(3)).revokeByTenant(captor.capture());
        revokedTenants.addAll(captor.getAllValues());
        assertThat(revokedTenants).as("父/子/孙逐租户撤会话")
                .containsExactlyInAnyOrder(FIXTURE_PREFIX + "-cas-p", FIXTURE_PREFIX + "-cas-c", FIXTURE_PREFIX + "-cas-g");
        verify(revocationGateway, times(1)).revokeByTenant(eq(FIXTURE_PREFIX + "-cas-p"));
        verify(revocationGateway, times(1)).revokeByTenant(eq(FIXTURE_PREFIX + "-cas-c"));
        verify(revocationGateway, times(1)).revokeByTenant(eq(FIXTURE_PREFIX + "-cas-g"));

        // 恢复父 → 子孙保持停用（显式逐个恢复），且恢复不触发撤销（既有口径：仅停用撤销）
        JsonNode restored = putStatus(chain[0], "ENABLED");
        assertThat(restored.path("success").asBoolean()).as("恢复父租户应成功：%s", restored).isTrue();
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-p")).isEqualTo("ENABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-c")).as("恢复父不自动恢复子").isEqualTo("DISABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-g")).as("恢复父不自动恢复孙").isEqualTo("DISABLED");
        verify(revocationGateway, times(3)).revokeByTenant(anyString());
        verifyNoMoreInteractions(revocationGateway);
    }

    // ---------- AC-6：tree 端点全量树形（status/level/套餐） ----------

    @Test
    void should_return_full_tree_with_status_level_and_package_when_platform_side_queries_tree() throws Exception {
        long packageId = createPackageOk(FIXTURE_PREFIX + "树形套餐");
        String parentCode = createTenantOk(FIXTURE_PREFIX + "-tree-p");
        long parentId = tenantIdByCode(parentCode);
        JsonNode bound = updateTenant(parentId, tenantNameOf(parentId), versionOf(parentId), null, packageId);
        assertThat(bound.path("success").asBoolean()).as("父租户绑定套餐应成功：%s", bound).isTrue();
        String childCode = createTenantOk(FIXTURE_PREFIX + "-tree-c", parentId);

        JsonNode tree = callAsSuperAdmin(get(LIST_URL + "/tree"));
        assertThat(tree.path("success").asBoolean()).as("tree 端点应成功：%s", tree).isTrue();
        JsonNode data = tree.path("data");
        JsonNode roots = data.isArray() ? data : data.path("records");
        assertThat(roots.isArray()).as("tree data 应为节点数组（裸数组或 records 包裹，AMB-4）").isTrue();

        JsonNode parentNode = findNodeByCode(roots, parentCode);
        assertThat(parentNode).as("tree 应含父租户节点").isNotNull();
        assertThat(parentNode.path("tenantCode").asText()).isEqualTo(parentCode);
        assertThat(parentNode.path("status").asText()).as("节点含 status").isEqualTo("ENABLED");
        assertThat(parentNode.path("level").asLong()).as("节点含 level").isEqualTo(1);
        assertThat(parentNode.path("packageId").asLong()).as("节点含套餐（父=绑定值）").isEqualTo(packageId);

        JsonNode childNode = findNodeByCode(parentNode.path("children"), childCode);
        assertThat(childNode).as("子节点应嵌套在父节点 children 内（树形而非平铺）").isNotNull();
        assertThat(childNode.path("level").asLong()).as("子节点 level=2").isEqualTo(2);
        assertThat(childNode.path("packageId").isNull() || childNode.path("packageId").isMissingNode())
                .as("子租户未绑套餐（套餐独立绑定）").isTrue();
    }

    // ---------- AC-7：父租户管理员不可见子租户业务数据（既有隔离口径回归） ----------

    @Test
    void should_keep_child_tenant_data_invisible_when_parent_tenant_admin_queries() throws Exception {
        // 父/子均走 init（父含管理员用户），子租户内直插一名业务用户
        initTenantOk(FIXTURE_PREFIX + "-iso-p", null);
        initTenantOk(FIXTURE_PREFIX + "-iso-c", tenantIdByCode(FIXTURE_PREFIX + "-iso-p"));
        seedUserInTenant("req102954-iso-child-user", FIXTURE_PREFIX + "-iso-c");

        // 父租户上下文：用户列表不含子租户用户；子租户上下文：反向亦然（双向独立，零旁路）
        JsonNode pageOfParent = callInTenant(FIXTURE_PREFIX + "-iso-p", get("/admin/v1/system/users")
                .param("page", "1").param("size", "50"));
        assertThat(containsUsername(pageOfParent, "req102954-iso-child-user"))
                .as("父租户不可见子租户业务数据（确认④零旁路）").isFalse();
        assertThat(containsUsername(pageOfParent, FIXTURE_PREFIX + "_iso_p_admin"))
                .as("父租户可见自己的管理员").isTrue();

        JsonNode pageOfChild = callInTenant(FIXTURE_PREFIX + "-iso-c", get("/admin/v1/system/users")
                .param("page", "1").param("size", "50"));
        assertThat(containsUsername(pageOfChild, "req102954-iso-child-user"))
                .as("子租户可见自己用户").isTrue();
        assertThat(containsUsername(pageOfChild, FIXTURE_PREFIX + "_iso_p_admin"))
                .as("子租户不可见父租户管理员（独立租户编码天然隔离）").isFalse();
    }

    // ---------- AC-8：子租户套餐独立，父变更不影响子 ----------

    @Test
    void should_keep_child_package_when_parent_package_changed() throws Exception {
        long packageA = createPackageOk(FIXTURE_PREFIX + "子套餐甲");
        long packageB = createPackageOk(FIXTURE_PREFIX + "父套餐乙");
        String parentCode = createTenantOk(FIXTURE_PREFIX + "-pkg-p");
        long parentId = tenantIdByCode(parentCode);
        long childId = tenantIdByCode(createTenantOk(FIXTURE_PREFIX + "-pkg-c", parentId));

        // 各自独立绑定：父=乙、子=甲
        assertThat(updateTenant(parentId, tenantNameOf(parentId), versionOf(parentId), null, packageB)
                .path("success").asBoolean()).isTrue();
        assertThat(updateTenant(childId, tenantNameOf(childId), versionOf(childId), null, packageA)
                .path("success").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where id = ?", Long.class, childId))
                .as("子租户绑定甲").isEqualTo(packageA);

        // 父变更（解除绑定）→ 子 package_id 不受影响
        assertThat(updateTenant(parentId, tenantNameOf(parentId), versionOf(parentId), null, null)
                .path("success").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where id = ?", Long.class, childId))
                .as("父变更套餐后子绑定不变（确认⑧独立生效）").isEqualTo(packageA);
    }

    // ---------- AC-11：既有租户域回归（default 不可停用 / 顶层面 / list 层级字段） ----------

    @Test
    void should_keep_existing_tenant_domain_behaviors_when_hierarchy_added() throws Exception {
        // ① default 租户不可停用（既有口径回归）
        long defaultId = tenantIdByCode("default");
        JsonNode denied = putStatus(defaultId, "DISABLED");
        assertThat(denied.path("success").asBoolean()).as("default 不可停用：%s", denied).isFalse();
        assertThat(denied.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_DISABLE_DENIED);
        assertThat(tenantStatus("default")).isEqualTo("ENABLED");

        // ② 简单 create 缺省 parentId → 顶层（parent NULL / level=1），既有创建面零回归
        String createdCode = createTenantOk(FIXTURE_PREFIX + "-base");
        Map<String, Object> createdRow = jdbcTemplate.queryForMap(
                "select parent_id, level from sys_tenant where tenant_code = ?", createdCode);
        assertThat(createdRow.get("parent_id")).as("缺省 parentId=顶层").isNull();
        assertThat(((Number) createdRow.get("level")).intValue()).isEqualTo(1);

        // ③ list 下发层级字段（契约确认⑨：list 加层级字段 parentId/level）
        JsonNode page = callAsSuperAdmin(get(LIST_URL).queryParam("page", "1").queryParam("size", "50"));
        assertThat(page.path("success").asBoolean()).isTrue();
        JsonNode records = page.path("data").path("records");
        assertThat(records.isArray() && records.size() > 0).isTrue();
        JsonNode defaultRecord = findNodeByCode(records, "default");
        assertThat(defaultRecord).as("list 应含 default 行").isNotNull();
        assertThat(defaultRecord.path("level").asLong()).as("list 层级字段 level").isEqualTo(1);
        assertThat(defaultRecord.path("parentId").isNull() || defaultRecord.path("parentId").isMissingNode())
                .as("list 层级字段 parentId（顶层 NULL）").isTrue();
    }

    // ---------- 夹具装配 ----------

    /** 建 P→C→G 三层链（全部走 POST create，无管理员），返回 {parentId, childId, grandchildId}。 */
    private long[] seedChain(String prefix) throws Exception {
        long parentId = tenantIdByCode(createTenantOk(prefix + "-p"));
        long childId = tenantIdByCode(createTenantOk(prefix + "-c", parentId));
        long grandchildId = tenantIdByCode(createTenantOk(prefix + "-g", childId));
        return new long[]{parentId, childId, grandchildId};
    }

    private static ObjectNode createBody(String tenantCode, Long parentId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 集成夹具租户");
        if (parentId == null) {
            body.putNull("parentId");
        } else {
            body.put("parentId", parentId);
        }
        return body;
    }

    private static ObjectNode initBody(String tenantCode, Long parentId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 开通夹具租户");
        body.putNull("remark");
        body.put("adminUsername", tenantCode.replace('-', '_') + "_admin");
        body.put("adminNickname", tenantCode + " 管理员");
        if (parentId == null) {
            body.putNull("parentId");
        } else {
            body.put("parentId", parentId);
        }
        return body;
    }

    /** init 开通（成功信封断言），返回响应体。 */
    private JsonNode initTenantOk(String tenantCode, Long parentId) throws Exception {
        JsonNode result = callAsSuperAdmin(post(LIST_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBody(tenantCode, parentId))));
        assertThat(result.path("success").asBoolean()).as("init 开通应成功：%s", result).isTrue();
        assertThat(result.path("code").asText()).isEqualTo("0");
        return result;
    }

    /** init 开通（仅返回租户码；供基线用例使用）。 */
    private String initTenant(String tenantCode, Long parentId) throws Exception {
        initTenantOk(tenantCode, parentId);
        return tenantCode;
    }

    /** 简单 create（成功信封断言），返回租户码。 */
    private String createTenantOk(String tenantCode) throws Exception {
        return createTenantOk(tenantCode, null);
    }

    private String createTenantOk(String tenantCode, Long parentId) throws Exception {
        JsonNode result = callAsSuperAdmin(post(LIST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(createBody(tenantCode, parentId))));
        assertThat(result.path("success").asBoolean()).as("create 租户应成功：%s", result).isTrue();
        assertThat(result.path("code").asText()).isEqualTo("0");
        return tenantCode;
    }

    /** 租户更新（parentId/packageId 可空=null 语义；乐观锁 version 随体提交）。 */
    private JsonNode updateTenant(long tenantId, String tenantName, long version, Long parentId, Long packageId)
            throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantName", tenantName);
        body.put("version", version);
        if (parentId == null) {
            body.putNull("parentId");
        } else {
            body.put("parentId", parentId);
        }
        if (packageId == null) {
            body.putNull("packageId");
        } else {
            body.put("packageId", packageId);
        }
        return callAsSuperAdmin(put(LIST_URL + "/" + tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** 停用/启用（成功路径 emptyAsSuccess 双口径兼容：空体 2xx 合成为成功信封；失败路径信封 code 断言用）。 */
    private JsonNode putStatus(long tenantId, String status) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("status", status);
        org.springframework.test.web.servlet.MvcResult result = performAs("1", "default", Set.of("super_admin"),
                put(LIST_URL + "/" + tenantId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)));
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (content.isBlank()) {
            assertThat(result.getResponse().getStatus())
                    .as("void 状态端点空体应 2xx（emptyAsSuccess 既有惯例）")
                    .isEqualTo(org.springframework.http.HttpStatus.OK.value());
            ObjectNode ok = JSON.createObjectNode();
            ok.put("success", true);
            ok.put("code", "0");
            return ok;
        }
        return JSON.readTree(content);
    }

    /** 创建套餐（仅名），返回套餐 id（REQ-022910 端点复用）。 */
    private long createPackageOk(String name) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("packageName", name);
        body.putArray("menuIds");
        JsonNode result = callAsSuperAdmin(post("/admin/v1/system/tenant-packages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean()).as("创建套餐应成功：%s", result).isTrue();
        return result.path("data").path("id").asLong();
    }

    // ---------- 查询支撑 ----------

    private long tenantIdByCode(String tenantCode) {
        Long id = tenantIdByCodeOrNull(tenantCode);
        assertThat(id).as("夹具租户应已落库：%s", tenantCode).isNotNull();
        return id;
    }

    private Long tenantIdByCodeOrNull(String tenantCode) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from sys_tenant where tenant_code = ?", Long.class, tenantCode);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String tenantNameOf(long tenantId) {
        return jdbcTemplate.queryForObject(
                "select tenant_name from sys_tenant where id = ?", String.class, tenantId);
    }

    private long versionOf(long tenantId) {
        return jdbcTemplate.queryForObject(
                "select version from sys_tenant where id = ?", Long.class, tenantId);
    }

    private String tenantStatus(String tenantCode) {
        return jdbcTemplate.queryForObject(
                "select status from sys_tenant where tenant_code = ?", String.class, tenantCode);
    }

    private void seedUserInTenant(String username, String tenantCode) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, 'ENABLED', false, now(), ?, now(), ?, 0, false)
                        """,
                Math.abs(System.identityHashCode(username)) & 0x7FFFFFFFL | 0x600000000L,
                tenantCode, username, username, OPERATOR, OPERATOR);
    }

    // ---------- HTTP 支撑（REQ-022910 先例口径） ----------

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return bodyOf(performAs("1", "default", Set.of("super_admin"), request));
    }

    /** 指定租户上下文调用（isolation IT 先例：super_admin 短路权限、TenantContext 随令牌绑定）。 */
    private JsonNode callInTenant(String tenant, MockHttpServletRequestBuilder request) throws Exception {
        return bodyOf(performAs("1", tenant, Set.of("super_admin"), request));
    }

    private MvcResult performAs(String userId, String tenant, Set<String> roles,
                                MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject(userId, tenant, com.acme.common.context.Channel.B, roles)))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 树/列表递归按 tenantCode 找节点（children 宽容：缺省/空数组均兼容，AMB-4）。 */
    private static JsonNode findNodeByCode(JsonNode nodes, String tenantCode) {
        if (nodes == null || !nodes.isArray()) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (tenantCode.equals(node.path("tenantCode").asText(null))) {
                return node;
            }
            JsonNode inChildren = findNodeByCode(node.path("children"), tenantCode);
            if (inChildren != null) {
                return inChildren;
            }
        }
        return null;
    }

    private static boolean containsUsername(JsonNode pageBody, String username) {
        for (JsonNode record : pageBody.path("data").path("records")) {
            if (username.equals(record.path("username").asText())) {
                return true;
            }
        }
        return false;
    }
}
