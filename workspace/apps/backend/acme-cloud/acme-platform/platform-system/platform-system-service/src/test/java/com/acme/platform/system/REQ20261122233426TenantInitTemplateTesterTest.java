package com.acme.platform.system;

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
import org.springframework.http.HttpStatus;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-233426（TENANT-5）租户开通初始化模板 tester 集成测试（Testcontainers PG，装配口径照
 * {@code REQ20261122150802TenantLifecycleQuotaIntegrationTest}/
 * {@code REQ20261121022910TenantPackageApiIntegrationTest} 先例；无 Docker 自动跳过——判定口径：
 * 日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-1/2/3/4（§确认记录②~④、§验收标准；AC-5 见
 * {@code REQ20261122233426UsageOverviewTesterTest}）：</p>
 * <ul>
 *   <li>AC-1：V32 迁移+重放幂等——sys_tenant_init_template 就绪（content jsonb/code uk/
 *       default_package_id 可空）+ 5 权限点种子（system:tenant-template:list/create/update/delete +
 *       system:tenant:usage-overview）+ 超管绑定幂等；菜单/BUTTON 计数水位不变（总数 55/BUTTON 33，
 *       契约钉死「V32 无新菜单节点」）；sys_permission 总数水位 82→87（坑 16 抬升纪律，本类为
 *       V32 交付方口径，历史测试抬升清单见交付报告）。</li>
 *   <li>AC-2：模板 CRUD 四端点全通 + content 非法拒绝（三个保留前缀 + 空角色集 → Result.code≠0，
 *       PARAM_INVALID 口径不断言具体码字面量）。</li>
 *   <li>AC-3：init 带 templateCode——按模板创建角色集/绑定授权（权限集=模板集非动态全量）/
 *       写 sys_config/默认套餐生效/adminUsername 模板前缀；模板=开通时快照语义（改模板不影响
 *       已开通租户，裁决记录①）。</li>
 *   <li>AC-4：init 未传/未命中 templateCode——现行为逐字不变（动态全量授权集兼容，确认③）。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 模板 wire 字段：请求体 {code,name,remark,content,defaultPackageId}、content 形状照
 *       确认②（adminUsernamePrefix/adminNickname/rolePresets[{roleKey,roleName,permissionCodes}]/
 *       configEntries[{configKey,value}]）；list 记录回读 content/defaultPackageId/version——字段
 *       缺失=实现披露缺口红灯锚点（REQ-022910 先例口径）。</li>
 *   <li>AMB-2 roleKey→sys_role.role_code 同形直译（匹配失败回退 role_name 匹配）；模板角色授权
 *       落 sys_role_permission（V1 schema 唯一权限绑定面）。</li>
 *   <li>AMB-3 「跳过动态授权集」断言锚：动态全量集必含非保留使能点（system:config:list/
 *       system:role:list/system:dict:list 均为真实种子码），模板集租户管理员 union 集不含这些金丝雀；
 *       若实现为开通管理员保留最小内建面，红灯交 TL 裁决。</li>
 *   <li>AMB-4 configEntries 落库形状：sys_config 现为每租户单行固定列（V15，无行租户按缺省生效），
 *       KV 细粒度落库形状契约未钉死——以「租户出现 ≥1 行 sys_config（模板路径）/0 行（动态路径）」
 *       承载，KV 映射细断言待实现披露后补强。</li>
 *   <li>AMB-5 权限点挂靠菜单 id 以 backend 实现披露为准（sys_permission 无 menu_id 列，V32 不新增
 *       菜单节点）——本类只钉权限码/方法/路径与超管绑定，不钉菜单挂靠 id。</li>
 *   <li>AMB-6 update/delete 路径参数占位符拼写（{id}）以实现披露为准，路径断言用前缀宽容。</li>
 *   <li>依赖（backend 并行交付，映射表注明）：V32 迁移、/admin/v1/system/tenant-templates 四端点、
 *       init 扩 templateCode。实现未落盘时本类红灯=实现缺口锚点非用例缺陷（tester 交付口径，
 *       附原样输出归因 TL）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-tenant-init-tpl-tester-secret-01"
})
class REQ20261122233426TenantInitTemplateTesterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req233426t-it";

    private static final String TENANTS_URL = "/admin/v1/system/tenants";

    private static final String TEMPLATES_URL = "/admin/v1/system/tenant-templates";

    private static final String PACKAGES_URL = "/admin/v1/system/tenant-packages";

    /** 夹具租户码前缀（teardown 按前缀物理清理；与开发侧 *IntegrationTest 前缀隔离防撞名）。 */
    private static final String FIXTURE_PREFIX = "req233426t";

    /** 模板 code 前缀（code uk；teardown 按前缀物理清理）。 */
    private static final String TEMPLATE_CODE_PREFIX = "tpl-req233426t-";

    /** V32 五权限点（契约确认⑤钉死码集；方法/路径照契约 apis 节）。 */
    private static final List<String> V32_PERMISSION_CODES = List.of(
            "system:tenant-template:list", "system:tenant-template:create",
            "system:tenant-template:update", "system:tenant-template:delete",
            "system:tenant:usage-overview");

    /** 保留域三前缀金丝雀（背景钉死 RESERVED_PERMISSION_PREFIXES；均取真实种子码）。 */
    private static final List<String> RESERVED_CANARY_CODES = List.of(
            "system:tenant:list", "system:tenant-package:list", "infra:audit:list");

    /** 动态全量集金丝雀（非保留使能真实种子码：V2/V15 种子；模板路径必须不含、动态路径必含）。 */
    private static final List<String> DYNAMIC_CANARY_CODES = List.of(
            "system:config:list", "system:role:list", "system:dict:list");

    /** 权限点总数水位：V27 基线 82 + V32 五点（坑 16 抬升纪律：V32 交付方新口径）。 */
    private static final long PERMISSION_COUNT_V32 = 87L;

    /** 菜单水位（契约钉死 V32 无新菜单节点）：V29 后总数 55 / BUTTON 33。 */
    private static final long MENU_COUNT_V32 = 55L;

    private static final long BUTTON_COUNT_V32 = 33L;

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

    /** 每用例唯一序号：模板 code / 租户码互不串扰。 */
    private int fixtureSeq;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        fixtureSeq = SEQ.incrementAndGet();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：按夹具前缀物理清理（先配置/绑定旁表再主表；模板表按 code 前缀）
        jdbcTemplate.update("delete from sys_config where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update(
                "delete from sys_tenant_quota where tenant_id in "
                        + "(select id from sys_tenant where tenant_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_permission where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_menu where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_init_template where code like ?", TEMPLATE_CODE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_package_menu");
        jdbcTemplate.update("delete from sys_tenant_package");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：V32 迁移就绪 + 5 权限点种子 + 超管绑定 + 水位不变 + 重放幂等 ----------

    @Test
    void should_seed_v32_template_table_five_permissions_and_super_admin_binding_idempotently_when_migration_replayed()
            throws Exception {
        // ① V32 迁移脚本存在（backend 并行交付锚点）
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:db/migration/system/V32__*.sql");
        assertThat(resources).as("V32 迁移脚本缺失（backend 未落盘或命名不符 V32__*.sql）").isNotEmpty();

        // ② sys_tenant_init_template 就绪（确认②：id/code uk/name/remark/content jsonb/
        //    default_package_id?/审计列）
        Integer tableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema='public' and table_name='sys_tenant_init_template'", Integer.class);
        assertThat(tableCount).as("sys_tenant_init_template 表就绪").isEqualTo(1);
        Map<String, Object> contentColumn = jdbcTemplate.queryForMap(
                "select udt_name, is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant_init_template' "
                        + "and column_name='content'");
        assertThat(String.valueOf(contentColumn.get("udt_name")))
                .as("content 应为 jsonb（确认②钉死）").isEqualTo("jsonb");
        assertThat(contentColumn.get("is_nullable")).as("content 可空语义不钉死（列存在即可）").isNotNull();
        Map<String, Object> codeColumn = jdbcTemplate.queryForMap(
                "select is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant_init_template' "
                        + "and column_name='code'");
        assertThat(codeColumn.get("is_nullable")).as("code 非空（uk 业务键）").isEqualTo("NO");
        Map<String, Object> defaultPackageColumn = jdbcTemplate.queryForMap(
                "select udt_name, is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant_init_template' "
                        + "and column_name='default_package_id'");
        assertThat(String.valueOf(defaultPackageColumn.get("udt_name")))
                .as("default_package_id 应为 bigint").isEqualTo("int8");
        assertThat(defaultPackageColumn.get("is_nullable"))
                .as("default_package_id 可空（确认②可选列）").isEqualTo("YES");
        Integer codeUniqueCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint where conrelid='sys_tenant_init_template'::regclass "
                        + "and contype='u'", Integer.class);
        Integer codeUniqueIndexCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname='public' "
                        + "and tablename='sys_tenant_init_template' and indexdef ilike '%unique%'", Integer.class);
        assertThat(Math.max(codeUniqueCount, codeUniqueIndexCount))
                .as("code 应有唯一约束/唯一索引（确认② code uk）").isGreaterThanOrEqualTo(1);

        // ③ 5 权限点种子：码集精确 + 方法/路径对齐契约 apis（AMB-5：不钉菜单挂靠 id；AMB-6：路径参数宽容）
        List<String> seededCodes = jdbcTemplate.queryForList(
                "select permission_code from sys_permission where permission_code in (?, ?, ?, ?, ?) "
                        + "and status = 'ENABLED' and deleted = false order by permission_code",
                String.class, V32_PERMISSION_CODES.toArray());
        assertThat(seededCodes).as("V32 五权限点应全部种子且 ENABLED").containsExactlyInAnyOrderElementsOf(
                V32_PERMISSION_CODES);
        assertThat(jdbcTemplate.queryForObject(
                "select http_method from sys_permission where permission_code = 'system:tenant-template:list'",
                String.class)).as("模板 list 权限点方法=GET").isEqualTo("GET");
        assertThat(jdbcTemplate.queryForObject(
                "select http_method from sys_permission where permission_code = 'system:tenant-template:create'",
                String.class)).as("模板 create 权限点方法=POST").isEqualTo("POST");
        assertThat(jdbcTemplate.queryForObject(
                "select http_method from sys_permission where permission_code = 'system:tenant-template:update'",
                String.class)).as("模板 update 权限点方法=PUT").isEqualTo("PUT");
        assertThat(jdbcTemplate.queryForObject(
                "select http_method from sys_permission where permission_code = 'system:tenant-template:delete'",
                String.class)).as("模板 delete 权限点方法=DELETE").isEqualTo("DELETE");
        assertThat(jdbcTemplate.queryForList(
                "select http_path from sys_permission where permission_code in "
                        + "('system:tenant-template:list', 'system:tenant-template:create') order by permission_code",
                String.class))
                .as("模板权限点路径前缀对齐契约 apis").allSatisfy(
                        path -> assertThat(path).startsWith("/admin/v1/system/tenant-templates"));
        assertThat(jdbcTemplate.queryForList(
                "select http_path from sys_permission where permission_code in "
                        + "('system:tenant-template:update', 'system:tenant-template:delete') order by permission_code",
                String.class))
                .as("模板 update/delete 路径含 {id} 段（占位符拼写 AMB-6 宽容）").allSatisfy(
                        path -> assertThat(path).startsWith("/admin/v1/system/tenant-templates/"));
        assertThat(jdbcTemplate.queryForObject(
                "select http_method || ' ' || http_path from sys_permission "
                        + "where permission_code = 'system:tenant:usage-overview'",
                String.class)).as("运营视图权限点=GET /admin/v1/system/tenants/usage-overview（契约 apis）")
                .isEqualTo("GET /admin/v1/system/tenants/usage-overview");

        // ④ 超管绑定（坑 16：新增权限点须超管绑定；default 租超管 role_id=1 先例口径）
        assertThat(superAdminBindingCount()).as("default 超管应补绑 V32 五权限点").isEqualTo(5L);

        // ⑤ 计数水位（契约钉死 V32 无新菜单节点：菜单/BUTTON 不变；权限点 82→87 坑 16 抬升）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .as("菜单总数水位不变（V29 水位 55；V32 无新菜单节点——防种子漂移钉死断言）")
                .isEqualTo(MENU_COUNT_V32);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class))
                .as("BUTTON 总数水位不变（V29 水位 33；V32 无新 BUTTON）").isEqualTo(BUTTON_COUNT_V32);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .as("权限点总数水位=V27 基线 82 + V32 五点（坑 16 抬升纪律）").isEqualTo(PERMISSION_COUNT_V32);

        // ⑥ 重放幂等：V32 种子 INSERT 原样重放后五面数据零变化（DDL 由 Flyway 一次性执行不在重放范围）
        long templatesBefore = templateRowCount();
        long menusBefore = jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class);
        long buttonsBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class);
        long permissionsBefore = jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class);
        long bindingsBefore = superAdminBindingCount();

        replayV32SeedInserts(resources);

        assertThat(templateRowCount()).as("重放后模板行零变化（幂等）").isEqualTo(templatesBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .as("重放后菜单总数零变化（幂等）").isEqualTo(menusBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class))
                .as("重放后 BUTTON 总数零变化（幂等）").isEqualTo(buttonsBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .as("重放后权限点总数零变化（幂等）").isEqualTo(permissionsBefore);
        assertThat(superAdminBindingCount()).as("重放后超管绑定零变化（幂等）").isEqualTo(bindingsBefore);
    }

    private long templateRowCount() {
        return jdbcTemplate.queryForObject("select count(*) from sys_tenant_init_template", Long.class);
    }

    private long superAdminBindingCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from sys_role_permission rp join sys_permission p on p.id = rp.permission_id "
                        + "where rp.role_id = 1 and rp.tenant_id = 'default' and p.permission_code in "
                        + "('" + String.join("', '", V32_PERMISSION_CODES) + "')", Long.class);
    }

    /** 定位并重放 V32 迁移的 INSERT 语句（文件名后缀未钉死按 V32__ 前缀解析；仅重放 INSERT，V24 先例口径）。 */
    private void replayV32SeedInserts(org.springframework.core.io.Resource[] resources) throws IOException {
        for (var resource : resources) {
            String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String chunk : script.split(";")) {
                String statement = chunk.lines()
                        .filter(line -> !line.strip().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .strip();
                if (statement.regionMatches(true, 0, "insert", 0, 6)) {
                    jdbcTemplate.execute(statement);
                }
            }
        }
    }

    // ---------- AC-2：模板 CRUD 四端点全通 ----------

    @Test
    void should_pass_full_crud_cycle_when_template_content_valid() throws Exception {
        long packageId = createPackageOk(FIXTURE_PREFIX + "-crud 套餐");
        String code = TEMPLATE_CODE_PREFIX + fixtureSeq + "-crud";
        String name = FIXTURE_PREFIX + " 初始模板";

        // ① create：合法 content + defaultPackageId → 成功落行
        JsonNode created = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(
                        templateBody(code, name, "集成样例模板", validContent(), packageId))));
        assertThat(created.path("success").asBoolean()).as("创建模板应成功：%s", created).isTrue();
        assertThat(created.path("code").asText()).as("成功信封 code=0").isEqualTo("0");
        long templateId = created.path("data").path("id").asLong();
        assertThat(templateId).as("create 应回显模板 id").isPositive();

        // ② code uk：同码二建 → 业务拒绝（确认② uk 语义）
        JsonNode duplicated = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(
                        templateBody(code, name + "-二建", null, validContent(), null))));
        assertRejected(duplicated, "同码重复创建（code uk）");

        // ③ list：分页命中 + content/defaultPackageId 回读（AMB-1 wire 锚点）
        JsonNode record = listRecordByCode(code);
        assertThat(record).as("list 应含新建模板行").isNotNull();
        assertThat(record.path("name").asText()).isEqualTo(name);
        assertThat(record.path("content").isMissingNode() || record.path("content").isNull())
                .as("list 应回读 content（缺失=实现披露缺口，AMB-1）").isFalse();
        assertThat(record.path("content").path("rolePresets").get(0).path("roleKey").asText())
                .as("content.rolePresets 回读").isEqualTo("tenant_admin");
        assertThat(textsOf(record.path("content").path("rolePresets").get(0).path("permissionCodes")))
                .as("content.permissionCodes 回读").containsExactly("system:user:list", "system:user:create");
        assertThat(record.path("content").path("configEntries").get(0).path("configKey").asText())
                .as("content.configEntries 回读").isEqualTo("tenant.greeting");
        assertThat(record.path("defaultPackageId").isMissingNode() || record.path("defaultPackageId").isNull())
                .as("defaultPackageId 回读缺失=实现披露缺口（AMB-1）").isFalse();
        assertThat(record.path("defaultPackageId").asLong()).as("defaultPackageId 回读=创建值").isEqualTo(packageId);

        // ④ update：改名 + 扩 permissionCodes（仍非保留前缀）→ 成功且 list 反映（乐观锁 version 随体，
        //    list 未披露 version 时省略——AMB-1）
        ObjectNode updatedContent = validContent();
        ((ArrayNode) updatedContent.path("rolePresets").get(0).path("permissionCodes")).add("system:dict:list");
        ObjectNode updateBody = templateBody(code, name + "-改", null, updatedContent, null);
        if (!record.path("version").isMissingNode() && !record.path("version").isNull()) {
            updateBody.put("version", record.path("version").asLong());
        }
        JsonNode updated = callAsSuperAdmin(put(TEMPLATES_URL + "/" + templateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody)));
        assertThat(updated.path("success").asBoolean()).as("更新模板应成功：%s", updated).isTrue();
        JsonNode afterUpdate = listRecordByCode(code);
        assertThat(afterUpdate.path("name").asText()).as("update 后名称应切换").isEqualTo(name + "-改");
        assertThat(textsOf(afterUpdate.path("content").path("rolePresets").get(0).path("permissionCodes")))
                .as("update 后 permissionCodes 应切换").containsExactly("system:user:list", "system:user:create",
                        "system:dict:list");

        // ⑤ delete：2xx（void emptyAsSuccess 双口径兼容）+ list 不再命中（软删过滤）
        MvcResult deleted = performAs("1", "default", Set.of("super_admin"),
                delete(TEMPLATES_URL + "/" + templateId));
        String content = deleted.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (content.isBlank()) {
            assertThat(deleted.getResponse().getStatus())
                    .as("void 删除端点空体应 2xx（emptyAsSuccess 既有惯例）")
                    .isEqualTo(HttpStatus.OK.value());
        } else {
            JsonNode deletedBody = JSON.readTree(content);
            assertThat(deletedBody.path("success").asBoolean()).as("删除模板应成功：%s", deletedBody).isTrue();
        }
        assertThat(listRecordByCode(code)).as("delete 后 list 不应再命中（软删过滤）").isNull();
    }

    // ---------- AC-2：content 非法拒绝（保留前缀/空角色集，保存时即拒） ----------

    @Test
    void should_reject_template_content_with_reserved_prefix_or_empty_role_set_when_saved() throws Exception {
        long before = templateRowCount();

        // ① 保留域三前缀逐一拒绝（背景钉死 RESERVED_PERMISSION_PREFIXES；均真实种子码）
        for (String reserved : RESERVED_CANARY_CODES) {
            String code = TEMPLATE_CODE_PREFIX + fixtureSeq + "-rsv";
            JsonNode rejected = callAsSuperAdmin(post(TEMPLATES_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(
                            templateBody(code, FIXTURE_PREFIX + " 非法模板", null,
                                    contentWithSinglePermission(reserved), null))));
            assertRejected(rejected, "保留前缀 " + reserved + " 模板保存");
        }

        // ② 空角色集拒绝（确认③「空角色集」保存即拒）
        String code = TEMPLATE_CODE_PREFIX + fixtureSeq + "-empty";
        ObjectNode emptyRoles = JSON.createObjectNode();
        emptyRoles.putArray("rolePresets");
        emptyRoles.putArray("configEntries");
        JsonNode rejectedEmpty = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(
                        templateBody(code, FIXTURE_PREFIX + " 空角色集模板", null, emptyRoles, null))));
        assertRejected(rejectedEmpty, "空 rolePresets 模板保存");

        // ③ update 改为非法 content 同拒，且原 content 不变（整体拒绝不半写）
        long packageId = createPackageOk(FIXTURE_PREFIX + "-reject 套餐");
        String validCode = TEMPLATE_CODE_PREFIX + fixtureSeq + "-valid";
        JsonNode created = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(templateBody(validCode, FIXTURE_PREFIX + " 合法模板",
                        null, validContent(), packageId))));
        assertThat(created.path("success").asBoolean()).as("前置：合法模板应创建成功：%s", created).isTrue();
        long templateId = created.path("data").path("id").asLong();
        JsonNode record = listRecordByCode(validCode);
        assertThat(record).as("前置：list 应含合法模板").isNotNull();
        ObjectNode illegalUpdate = templateBody(validCode, FIXTURE_PREFIX + " 合法模板", null,
                contentWithSinglePermission("system:tenant:list"), packageId);
        if (!record.path("version").isMissingNode() && !record.path("version").isNull()) {
            illegalUpdate.put("version", record.path("version").asLong());
        }
        JsonNode rejectedUpdate = callAsSuperAdmin(put(TEMPLATES_URL + "/" + templateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(illegalUpdate)));
        assertRejected(rejectedUpdate, "update 为保留前缀 content");
        JsonNode after = listRecordByCode(validCode);
        assertThat(textsOf(after.path("content").path("rolePresets").get(0).path("permissionCodes")))
                .as("拒绝后原 content 不变").containsExactly("system:user:list", "system:user:create");

        // ④ 非法保存不落行
        assertThat(templateRowCount()).as("非法 content 保存不应落行").isEqualTo(before + 1);
    }

    // ---------- AC-3：init 带 templateCode（角色/授权/配置/默认套餐 + 跳过动态授权集 + 快照语义） ----------

    @Test
    void should_initialize_tenant_from_template_and_freeze_snapshot_when_init_with_template_code()
            throws Exception {
        long packageId = createPackageOk(FIXTURE_PREFIX + "-init 套餐");
        String templateCode = TEMPLATE_CODE_PREFIX + fixtureSeq + "-init";
        JsonNode created = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(templateBody(templateCode, FIXTURE_PREFIX + " 开通模板",
                        null, validContent(), packageId))));
        assertThat(created.path("success").asBoolean()).as("前置：模板应创建成功：%s", created).isTrue();

        // ① init 带模板 → 成功 + AdminTenantInitView 全字段（背景钉死形状）
        String tenantCode = FIXTURE_PREFIX + "-tpl-init";
        JsonNode view = callAsSuperAdmin(post(TENANTS_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBody(tenantCode, templateCode, "plainadmin"))));
        assertThat(view.path("success").asBoolean()).as("init 带模板应成功：%s", view).isTrue();
        assertThat(view.path("code").asText()).isEqualTo("0");
        JsonNode data = view.path("data");
        for (String field : List.of("tenantId", "tenantCode", "tenantName",
                "adminUserId", "adminUsername", "adminNickname")) {
            assertThat(data.path(field).isMissingNode())
                    .as("AdminTenantInitView 应含 " + field + "（背景钉死形状）").isFalse();
        }
        assertThat(data.path("tenantCode").asText()).isEqualTo(tenantCode);
        long adminUserId = data.path("adminUserId").asLong();

        // ② adminUsername 模板前缀生效（确认③「adminUsername 优先模板前缀」；拼接规则 AMB 容差）
        assertThat(data.path("adminUsername").asText())
                .as("adminUsername 应带模板前缀 tpl_（前缀生效锚点；拼接规则以实现披露为准）")
                .startsWith("tpl_");

        // ③ 模板角色集创建（AMB-2：roleKey→role_code 直译，回退 role_name 匹配）
        long templateRoleId = templateRoleIdOf(tenantCode);
        assertThat(permissionCodesOfRole(templateRoleId, tenantCode))
                .as("模板角色授权=模板集精确（非动态全量——AC-3「跳过动态授权集」主锚；集合等价不钉序）")
                .containsExactlyInAnyOrder("system:user:list", "system:user:create");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user_role where tenant_id = ? and user_id = ? and role_id = ?",
                Long.class, tenantCode, adminUserId, templateRoleId))
                .as("开通管理员应绑定模板角色").isEqualTo(1L);

        // ④ 管理员 union 权限集：不含动态金丝雀（跳过动态授权集）+ 不含保留域三码
        List<String> union = unionPermissionCodesOfUser(tenantCode, adminUserId);
        assertThat(union).as("管理员 union 集应含模板码").contains("system:user:list", "system:user:create");
        assertThat(union)
                .as("管理员 union 集不应含动态全量金丝雀（跳过动态授权集，确认③；AMB-3）")
                .doesNotContainAnyElementsOf(DYNAMIC_CANARY_CODES);
        assertThat(union)
                .as("管理员 union 集不应含保留域三码（保留域不授租户管理员，既有不变量）")
                .doesNotContainAnyElementsOf(RESERVED_CANARY_CODES);

        // ⑤ sys_config 写入（确认② configEntries 写 sys_config 租户域；AMB-4 行存在性锚点）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_config where tenant_id = ?", Long.class, tenantCode))
                .as("模板路径应写租户 sys_config（动态路径无行——V15 口径，AMB-4）").isGreaterThanOrEqualTo(1L);

        // ⑥ 默认套餐生效（确认③）
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where tenant_code = ?", Long.class, tenantCode))
                .as("模板 defaultPackageId 应随开通绑定").isEqualTo(packageId);

        // ⑦ 快照语义（裁决记录①：改模板不影响已开通租户——开通时快照非引用绑定）
        ObjectNode changedContent = validContent();
        ((ArrayNode) changedContent.path("rolePresets").get(0).path("permissionCodes")).add("system:role:list");
        ObjectNode updateBody = templateBody(templateCode, FIXTURE_PREFIX + " 开通模板-改", null,
                changedContent, null);
        JsonNode record = listRecordByCode(templateCode);
        if (!record.path("version").isMissingNode() && !record.path("version").isNull()) {
            updateBody.put("version", record.path("version").asLong());
        }
        JsonNode updated = callAsSuperAdmin(put(TEMPLATES_URL + "/" + templateCodeToId(templateCode))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody)));
        assertThat(updated.path("success").asBoolean()).as("模板更新应成功：%s", updated).isTrue();
        assertThat(permissionCodesOfRole(templateRoleId, tenantCode))
                .as("改模板后已开通租户授权集不变（开通时快照语义，裁决记录①）")
                .containsExactlyInAnyOrder("system:user:list", "system:user:create");
    }

    // ---------- 坑 16 生效面：无种子权限点访问模板端点 → 403（既有 RBAC 口径） ----------

    @Test
    void should_reject_template_list_without_seed_permission_per_existing_rbac() throws Exception {
        // 无权限装配（MessageApi/TenantPackage 先例）：令牌角色无 sys_role 行 → 权限解析空集；
        // 403 证明 V32 种子权限点真实挂接端点（种子「生效」面，坑 16 验证口径）
        Set<String> noPermissionRoles = Set.of(FIXTURE_PREFIX + "_unbound_role");
        MvcResult result = performAs("1", "default", noPermissionRoles,
                get(TEMPLATES_URL).queryParam("page", "1").queryParam("size", "50"));
        assertThat(result.getResponse().getStatus())
                .as("无 system:tenant-template:list 权限应 403（PERMISSION_DENIED 登记 403）")
                .isEqualTo(403);
        JsonNode body = bodyOf(result);
        assertThat(body.path("success").asBoolean()).as("403 以 Result 信封表达").isFalse();
        assertThat(templateRowCount()).as("无权请求不触碰数据面").isZero();
    }

    // ---------- 夹具装配 ----------

    private static ObjectNode validContent() {
        ObjectNode content = JSON.createObjectNode();
        content.put("adminUsernamePrefix", "tpl_");
        content.put("adminNickname", "模板管理员");
        ObjectNode presets = content.putArray("rolePresets").addObject();
        presets.put("roleKey", "tenant_admin");
        presets.put("roleName", "模板租户管理员");
        ArrayNode codes = presets.putArray("permissionCodes");
        codes.add("system:user:list");
        codes.add("system:user:create");
        ObjectNode entry = content.putArray("configEntries").addObject();
        entry.put("configKey", "tenant.greeting");
        entry.put("value", "你好");
        return content;
    }

    private static ObjectNode contentWithSinglePermission(String permissionCode) {
        ObjectNode content = JSON.createObjectNode();
        ObjectNode presets = content.putArray("rolePresets").addObject();
        presets.put("roleKey", "tenant_admin");
        presets.put("roleName", "模板租户管理员");
        presets.putArray("permissionCodes").add(permissionCode);
        content.putArray("configEntries");
        return content;
    }

    private static ObjectNode templateBody(String code, String name, String remark,
                                           ObjectNode content, Long defaultPackageId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("code", code);
        body.put("name", name);
        if (remark == null) {
            body.putNull("remark");
        } else {
            body.put("remark", remark);
        }
        body.set("content", content);
        if (defaultPackageId == null) {
            body.putNull("defaultPackageId");
        } else {
            body.put("defaultPackageId", defaultPackageId);
        }
        return body;
    }

    private static ObjectNode initBody(String tenantCode, String templateCode, String adminUsername) {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 开通夹具租户");
        body.putNull("remark");
        body.put("adminUsername", adminUsername);
        body.put("adminNickname", tenantCode + " 管理员");
        body.putNull("parentId");
        if (templateCode != null) {
            body.put("templateCode", templateCode);
        }
        return body;
    }

    /** 创建套餐（仅名），返回套餐 id（REQ-022910 端点复用）。 */
    private long createPackageOk(String name) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("packageName", name);
        body.putArray("menuIds");
        JsonNode result = callAsSuperAdmin(post(PACKAGES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean()).as("创建套餐应成功：%s", result).isTrue();
        return result.path("data").path("id").asLong();
    }

    private JsonNode listRecordByCode(String code) throws Exception {
        JsonNode page = callAsSuperAdmin(get(TEMPLATES_URL)
                .queryParam("page", "1").queryParam("size", "50"));
        assertThat(page.path("success").asBoolean()).as("模板 list 应成功：%s", page).isTrue();
        JsonNode records = page.path("data").path("records");
        if (!records.isArray()) {
            records = page.path("data");
        }
        for (JsonNode node : records) {
            if (code.equals(node.path("code").asText(null))) {
                return node;
            }
        }
        return null;
    }

    private long templateCodeToId(String code) throws Exception {
        JsonNode record = listRecordByCode(code);
        assertThat(record).as("模板应已落库：%s", code).isNotNull();
        return record.path("id").asLong();
    }

    /** 模板角色 id（AMB-2：roleKey→role_code 直译，回退 role_name 匹配；双失配=实现披露红灯锚点）。 */
    private long templateRoleIdOf(String tenantCode) {
        List<Long> roleIds = jdbcTemplate.queryForList(
                "select id from sys_role where tenant_id = ? and deleted = false "
                        + "and (role_code = 'tenant_admin' or role_name = '模板租户管理员')",
                Long.class, tenantCode);
        assertThat(roleIds).as("模板路径应创建模板角色（roleKey tenant_admin / 角色名匹配）").isNotEmpty();
        return roleIds.get(0);
    }

    private List<String> permissionCodesOfRole(long roleId, String tenantCode) {
        return jdbcTemplate.queryForList(
                "select p.permission_code from sys_role_permission rp "
                        + "join sys_permission p on p.id = rp.permission_id "
                        + "where rp.tenant_id = ? and rp.role_id = ? order by p.permission_code",
                String.class, tenantCode, roleId);
    }

    private List<String> unionPermissionCodesOfUser(String tenantCode, long userId) {
        return jdbcTemplate.queryForList(
                "select distinct p.permission_code from sys_user_role ur "
                        + "join sys_role_permission rp on rp.role_id = ur.role_id and rp.tenant_id = ur.tenant_id "
                        + "join sys_permission p on p.id = rp.permission_id "
                        + "where ur.tenant_id = ? and ur.user_id = ? order by p.permission_code",
                String.class, tenantCode, userId);
    }

    private List<String> textsOf(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode.isArray()) {
            arrayNode.forEach(node -> values.add(node.asText()));
        }
        return values;
    }

    private static void assertRejected(JsonNode body, String what) {
        assertThat(body.path("success").asBoolean()).as(what + " 应被拒绝：%s", body).isFalse();
        assertThat(body.path("code").asText())
                .as(what + " 业务错误=统一响应体 Result.code≠0（PARAM_INVALID 口径不钉字面量）")
                .isNotEqualTo("0");
    }

    // ---------- HTTP 支撑（TENANT-4/REQ-022910 先例口径） ----------

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return bodyOf(performAs("1", "default", Set.of("super_admin"), request));
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
}
