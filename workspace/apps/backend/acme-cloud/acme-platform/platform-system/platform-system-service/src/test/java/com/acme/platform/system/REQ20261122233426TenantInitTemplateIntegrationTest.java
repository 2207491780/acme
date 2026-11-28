package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.interfaces.AdminTenantInitTemplateController;
import com.acme.platform.system.interfaces.UsageOverviewController;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * REQ-20261122-233426 租户开通初始化模板+平台运营视图（TENANT-5）platform-system 服务域集成测试
 * （Testcontainers PG，装配口径照 {@code REQ20261122150802TenantLifecycleQuotaIntegrationTest}/
 * {@code SystemIntegrationTest}；无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0
 * 才算验证）。
 *
 * <p>覆盖契约 AC-1~AC-5 后端面（§确认记录②~⑥、§验收标准）：</p>
 * <ul>
 *   <li>AC-1：V32 就绪（sys_tenant_init_template 新表 content jsonb + code uk）+ 5 权限点种子
 *       （83~87，挂靠租户管理菜单 id=7 无菜单节点）+ 超管绑定（87~91）幂等；菜单/BUTTON 计数水位
 *       55/33 不变；脚本重放幂等。</li>
 *   <li>AC-2：模板 CRUD 四端点全通；content 非法（保留前缀/空角色集/重复 roleKey/未知配置键/
 *       配置值不可解析）保存被拒（TENANT_TEMPLATE_CONTENT_INVALID，PARAM_INVALID 口径）。</li>
 *   <li>AC-3：init 带 templateCode → 按模板快照建角色集/绑授权（预设码权限+菜单投影）/写 sys_config/
 *       默认套餐；跳过动态授权集（预设外权限不授）；adminUsername=模板前缀优先（前缀+租户编码，
 *       确认③字面口径——与 tester TesterTest 同读）、昵称=请求显式值优先。</li>
 *   <li>AC-4：init 未传/未命中 templateCode → 现行为逐字不变（tenant_admin 动态全量授权、
 *       保留域含新增 system:tenant-template:/system:tenant:usage-overview 不授）。</li>
 *   <li>AC-5：GET /tenants/usage-overview 跨租户旁路聚合（totalTenants/四态计数/套餐分布
 *       packageDistribution（TL 裁决④补列：未绑定聚合为 null 项，msm 口径）/TopN
 *       storageBytes 降序，逐租户复用 TenantQuotaUseCase.usage）。</li>
 * </ul>
 *
 * <p>口径说明（TL 裁决已回填，余项就地标注）：</p>
 * <ul>
 *   <li>裁决① configEntries 词汇：sys_config 为固定列 schema——保存=键非空白不重复即入库
 *       （快照原样）；已知键（六键 camelCase 词汇）值可解析性保存校验+开通时写新租户 sys_config 行
 *       （平台缺省基座上覆盖）；未知键开通时 WARN 跳过（无处可写，不阻断开通）。</li>
 *   <li>裁决② adminUsername 前缀优先（前缀+租户编码保证全局唯一）；昵称=请求显式值优先、模板兜底。</li>
 *   <li>裁决③ 模板路径菜单绑定=预设码投影（码 ∈ 预设集的菜单节点+祖先目录补全，grantableMenus
 *       同构、范围限定预设码），跳过动态全量。</li>
 *   <li>未命中模板（templateCode 查无此码）= 现行为逐字不变（确认③「未传/未命中」字面口径，
 *       不显式报错）。</li>
 *   <li>tenantUsages.status=派生四视觉态（ENABLED/GRACE/EXPIRED/DISABLED，与 statusCounts
 *       同词汇；无到期租户与 raw 值同形——tester AMB-2 同口径）。</li>
 * </ul>
 */
class REQ20261122233426TenantInitTemplateIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req233426-it";

    private static final String TENANTS_URL = "/admin/v1/system/tenants";

    private static final String TEMPLATES_URL = "/admin/v1/system/tenant-templates";

    private static final String OVERVIEW_URL = "/admin/v1/system/tenants/usage-overview";

    /** 夹具前缀（teardown 按前缀物理清理；租户码/模板码/管理员用户名共用）。 */
    private static final String FIXTURE_PREFIX = "req233426";

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private com.acme.framework.security.support.InternalTokenVerifier tokenVerifier;

    @Autowired
    private com.acme.framework.security.support.InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new com.acme.framework.security.interceptor.InternalTokenAuthFilter(
                        tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 夹具脏数据 teardown：按夹具前缀物理清理（先旁表再主表；模板表按码前缀清理）
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select to_regclass('sys_file') is not null", Boolean.class))) {
            jdbcTemplate.update("delete from sys_file where tenant_id like ?", FIXTURE_PREFIX + "%");
        }
        jdbcTemplate.update("delete from sys_config where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_quota where tenant_id in "
                + "(select id from sys_tenant where tenant_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_menu where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_permission where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_package_menu where package_id in "
                + "(select id from sys_tenant_package where package_name like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_package where package_name like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant_init_template where code like ?", FIXTURE_PREFIX + "%");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：V32 就绪 + 5 权限点种子 + 超管绑定幂等 + 菜单水位不变 ----------

    @Test
    void should_apply_v32_template_table_and_seed_five_permissions_menu_neutral_and_be_idempotent()
            throws Exception {
        // ① 迁移脚本存在（backend 并行交付锚点）
        var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V32__*.sql");
        assertThat(resources).as("V32 迁移脚本缺失（backend 未落盘或命名不符 V32__*.sql）").isNotEmpty();

        // ② sys_tenant_init_template 就绪：content jsonb 非空 + code uk + default_package_id 可空
        Integer tableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' "
                        + "and table_name='sys_tenant_init_template'", Integer.class);
        assertThat(tableCount).as("sys_tenant_init_template 表就绪").isEqualTo(1);
        Map<String, Object> contentColumn = jdbcTemplate.queryForMap(
                "select udt_name, is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant_init_template' "
                        + "and column_name='content'");
        assertThat(String.valueOf(contentColumn.get("udt_name"))).as("content 应为 jsonb")
                .isEqualTo("jsonb");
        assertThat(contentColumn.get("is_nullable")).as("content 非空（保存时校验产物）").isEqualTo("NO");
        Integer uniqueCodeCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint where conrelid='sys_tenant_init_template'::regclass "
                        + "and contype='u'", Integer.class);
        assertThat(uniqueCodeCount).as("code uk（确认②）").isGreaterThanOrEqualTo(1);
        Map<String, Object> packageColumn = jdbcTemplate.queryForMap(
                "select is_nullable from information_schema.columns where table_schema='public' "
                        + "and table_name='sys_tenant_init_template' and column_name='default_package_id'");
        assertThat(packageColumn.get("is_nullable")).as("default_package_id 可空=NULL 不设默认套餐")
                .isEqualTo("YES");

        // ③ 5 权限点种子（83~87：模板四点 + 运营总览一点；纯 API 权限点无菜单节点——V26 先例）
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where id between 83 and 87 order by id",
                String.class))
                .as("V32 种子权限点 83~87（确认⑤）")
                .containsExactly("system:tenant-template:list", "system:tenant-template:create",
                        "system:tenant-template:update", "system:tenant-template:delete",
                        "system:tenant:usage-overview");
        assertThat(jdbcTemplate.queryForList(
                "select http_method || ' ' || http_path from sys_permission "
                        + "where id between 83 and 87 order by id", String.class))
                .as("接口级 http_method + http_path 登记（V26 先例口径）")
                .containsExactly("GET /admin/v1/system/tenant-templates",
                        "POST /admin/v1/system/tenant-templates",
                        "PUT /admin/v1/system/tenant-templates/{id}",
                        "DELETE /admin/v1/system/tenant-templates/{id}",
                        "GET /admin/v1/system/tenants/usage-overview");

        // ④ 超管角色绑定随种子幂等写入（绑定行 87~91 → 权限点 83~87；default 租超管 role_id=1）
        assertThat(jdbcTemplate.queryForList(
                "select rp.id from sys_role_permission rp where rp.id between 87 and 91 order by rp.id",
                Long.class)).as("超管绑定行 87~91").containsExactly(87L, 88L, 89L, 90L, 91L);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_role_permission rp
                join sys_permission p on p.id = rp.permission_id
                join sys_role r on r.id = rp.role_id
                where (p.permission_code like 'system:tenant-template:%'
                       or p.permission_code = 'system:tenant:usage-overview')
                  and r.role_code = 'super_admin' and r.tenant_id = 'default'
                  and rp.deleted = false
                """, Long.class)).as("超管绑定新 5 点（坑 16 不变量）").isEqualTo(5L);

        // ⑤ 菜单/BUTTON 计数水位不变（确认⑤：V32 禁新增菜单节点，55/33 不动）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .as("sys_menu 总数 55 水位不变").isEqualTo(55L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class))
                .as("BUTTON 33 水位不变").isEqualTo(33L);

        // ⑥ 重放幂等：语句原样重放后数据面零变化（DDL IF NOT EXISTS + 种子 ON CONFLICT DO NOTHING）
        List<Map<String, Object>> before = jdbcTemplate.queryForList(
                "select id, permission_code from sys_permission where id >= 83 order by id");
        List<Map<String, Object>> beforeBindings = jdbcTemplate.queryForList(
                "select id, permission_id from sys_role_permission where id >= 87 order by id");
        replayV32Statements(resources);
        assertThat(jdbcTemplate.queryForList(
                "select id, permission_code from sys_permission where id >= 83 order by id"))
                .as("重放后权限点零变化（幂等）").isEqualTo(before);
        assertThat(jdbcTemplate.queryForList(
                "select id, permission_id from sys_role_permission where id >= 87 order by id"))
                .as("重放后绑定行零变化（幂等）").isEqualTo(beforeBindings);
    }

    private void replayV32Statements(org.springframework.core.io.Resource[] resources) throws IOException {
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

    // ---------- AC-2：模板 CRUD 四端点 + content 非法保存被拒 ----------

    @Test
    void should_crud_templates_and_reject_invalid_content_when_template_managed() throws Exception {
        // ① 创建（content 结构化往返：rolePresets/configEntries/前缀/昵称/默认套餐）
        long packageId = createPackageOk(FIXTURE_PREFIX + "-tpl-pkg");
        JsonNode created = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(
                        templateBody(FIXTURE_PREFIX + "-tpl-a", "模板A", buildContent(), packageId))));
        assertThat(created.path("success").asBoolean()).as("create 模板应成功：%s", created).isTrue();
        JsonNode createdData = created.path("data");
        assertThat(createdData.path("code").asText()).isEqualTo(FIXTURE_PREFIX + "-tpl-a");
        assertThat(createdData.path("content").path("rolePresets").size())
                .as("rolePresets 往返").isEqualTo(2);
        assertThat(createdData.path("content").path("rolePresets").get(0).path("roleKey").asText())
                .isEqualTo("ops_admin");
        assertThat(createdData.path("content").path("configEntries").size())
                .as("configEntries 往返").isEqualTo(2);
        assertThat(createdData.path("defaultPackageId").asLong()).as("默认套餐透传").isEqualTo(packageId);
        long templateId = createdData.path("id").asLong();

        // ② 分页（keyword 对 code/name 模糊）
        JsonNode page = callAsSuperAdmin(get(TEMPLATES_URL)
                .queryParam("keyword", FIXTURE_PREFIX + "-tpl-a"));
        assertThat(page.path("success").asBoolean()).as("list 应成功：%s", page).isTrue();
        assertThat(page.path("data").path("total").asLong()).as("keyword 命中创建行").isEqualTo(1L);

        // ③ 重码拒绝（TENANT_TEMPLATE_DUPLICATE）
        JsonNode duplicated = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(templateBody(FIXTURE_PREFIX + "-tpl-a", "撞码模板",
                        buildContent(), null))));
        assertThat(duplicated.path("success").asBoolean()).as("重码应拒绝").isFalse();
        assertThat(duplicated.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_TEMPLATE_DUPLICATE);

        // ④ 更新（乐观锁版本；content 全量替换——快照语义）；旧版本重放 → 并发冲突
        ObjectNode updateBody = templateBody(FIXTURE_PREFIX + "-tpl-b", "模板A改", buildContent(), null);
        updateBody.put("version", createdData.path("version").asLong());
        JsonNode updated = callAsSuperAdmin(put(TEMPLATES_URL + "/" + templateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody)));
        assertThat(updated.path("success").asBoolean()).as("update 应成功：%s", updated).isTrue();
        assertThat(updated.path("data").path("code").asText()).isEqualTo(FIXTURE_PREFIX + "-tpl-b");
        JsonNode stale = callAsSuperAdmin(put(TEMPLATES_URL + "/" + templateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody)));
        assertThat(stale.path("success").asBoolean()).as("旧版本应冲突").isFalse();

        // ⑤ content 非法保存被拒（TENANT_TEMPLATE_CONTENT_INVALID，PARAM_INVALID 口径）
        for (Map.Entry<String, JsonNode> invalid : invalidContents().entrySet()) {
            JsonNode rejected = callAsSuperAdmin(post(TEMPLATES_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(templateBody(
                            FIXTURE_PREFIX + "-tpl-bad-" + invalid.getKey(), "非法模板",
                            invalid.getValue(), null))));
            assertThat(rejected.path("success").asBoolean())
                    .as("content 非法应拒绝（%s）：%s", invalid.getKey(), rejected).isFalse();
            assertThat(rejected.path("code").asText())
                    .as("content 非法码（%s）", invalid.getKey())
                    .isEqualTo(SystemErrorCodes.TENANT_TEMPLATE_CONTENT_INVALID);
        }

        // ⑥ 删除（软删；再 update → TENANT_TEMPLATE_NOT_FOUND）
        callAsSuperAdminOk(delete(TEMPLATES_URL + "/" + templateId), "delete 模板应成功");
        JsonNode missing = callAsSuperAdmin(put(TEMPLATES_URL + "/" + templateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody)));
        assertThat(missing.path("success").asBoolean()).as("已删模板不可静默更新").isFalse();
        assertThat(missing.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_TEMPLATE_NOT_FOUND);
    }

    /** content 非法集（键=用例锚点）：保留前缀×2 / 空角色集 / 重复 roleKey / 已知键配置值不可解析。 */
    private static Map<String, JsonNode> invalidContents() {
        ObjectNode reservedTenant = buildContent();
        setPresetCodes(reservedTenant, 0, "system:tenant:list");
        ObjectNode reservedTemplate = buildContent();
        setPresetCodes(reservedTemplate, 0, "system:tenant-template:list");
        ObjectNode emptyRoleSet = buildContent();
        ((ObjectNode) emptyRoleSet).set("rolePresets", JSON.createArrayNode());
        ObjectNode duplicateRoleKey = buildContent();
        ArrayNode dupPresets = JSON.createArrayNode();
        dupPresets.add(presetNode("dup", "角色甲", "system:user:list"));
        dupPresets.add(presetNode("dup", "角色乙", "system:user:list"));
        ((ObjectNode) duplicateRoleKey).set("rolePresets", dupPresets);
        ObjectNode unparsableIdle = buildContent();
        ((ObjectNode) unparsableIdle).set("configEntries", JSON.createArrayNode()
                .add(configEntryNode("lockIdleTimeoutMs", "garbage")));
        Map<String, JsonNode> invalid = new LinkedHashMap<>();
        invalid.put("reservedTenant", reservedTenant);
        invalid.put("reservedTemplate", reservedTemplate);
        invalid.put("emptyRoleSet", emptyRoleSet);
        invalid.put("duplicateRoleKey", duplicateRoleKey);
        invalid.put("unparsableIdle", unparsableIdle);
        return invalid;
    }

    private static void setPresetCodes(ObjectNode content, int presetIndex, String... codes) {
        ObjectNode preset = (ObjectNode) content.path("rolePresets").get(presetIndex);
        ArrayNode array = JSON.createArrayNode();
        for (String code : codes) {
            array.add(code);
        }
        preset.set("permissionCodes", array);
    }

    // ---------- AC-3：init 带模板 → 快照角色集/授权/配置/默认套餐，跳过动态授权集 ----------

    @Test
    void should_init_with_template_snapshot_when_template_code_hit() throws Exception {
        // ① 模板就绪：双预设角色 + 六键配置 + 默认套餐
        long packageId = createPackageOk(FIXTURE_PREFIX + "-init-pkg");
        String templateCode = FIXTURE_PREFIX + "-init-tpl";
        createTemplateOk(templateCode, "开通模板", buildFullContent(), packageId);

        // ② 开通（adminUsername/adminNickname 缺席 → 模板前缀/昵称兜底）
        String tenantCode = FIXTURE_PREFIX + "-init-t";
        JsonNode inited = callAsSuperAdmin(post(TENANTS_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBodyWithTemplate(tenantCode, templateCode,
                        null, null))));
        assertThat(inited.path("success").asBoolean()).as("模板开通应成功：%s", inited).isTrue();
        JsonNode initData = inited.path("data");
        assertThat(initData.path("adminUsername").asText())
                .as("adminUsername 缺席=模板前缀+租户编码（确认③优先模板前缀）")
                .isEqualTo("tpladm" + tenantCode);
        assertThat(initData.path("adminNickname").asText())
                .as("adminNickname 缺席=模板昵称兜底").isEqualTo("模板管理员");

        // ③ 默认套餐快照应用
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where tenant_code = ?", Long.class, tenantCode))
                .as("默认套餐随模板应用（确认③）").isEqualTo(packageId);

        // ④ 按模板建角色集：恰两角色（无 tenant_admin——跳过动态授权集计算）
        List<Map<String, Object>> roles = jdbcTemplate.queryForList(
                "select id, role_code, built_in from sys_role where tenant_id = ?", tenantCode);
        assertThat(roles).as("按模板建角色集（无动态 tenant_admin）").hasSize(2);
        Map<String, Long> roleIdByCode = new LinkedHashMap<>();
        roles.forEach(row -> {
            assertThat(Boolean.TRUE.equals(row.get("built_in")))
                    .as("模板角色非内置").isFalse();
            roleIdByCode.put(String.valueOf(row.get("role_code")), ((Number) row.get("id")).longValue());
        });
        assertThat(roleIdByCode).containsKeys("ops_admin", "viewer");
        Long opsRoleId = roleIdByCode.get("ops_admin");
        Long viewerRoleId = roleIdByCode.get("viewer");

        // ⑤ 绑定授权=预设码全集（快照授权集）；预设外权限不授（跳过动态全量授权集）
        assertThat(permissionCodesOfRole(opsRoleId))
                .as("ops_admin 授权=预设码全集").containsExactlyInAnyOrder(
                        "system:user:list", "system:user:create");
        assertThat(permissionCodesOfRole(viewerRoleId))
                .as("viewer 授权=预设码全集").containsExactly("system:role:list");
        assertThat(jdbcTemplate.queryForList("""
                select p.permission_code from sys_role_permission rp
                join sys_permission p on p.id = rp.permission_id
                where rp.tenant_id = ? and p.permission_code not in
                      ('system:user:list', 'system:user:create', 'system:role:list')
                """, String.class, tenantCode))
                .as("预设外权限不授（system:dept:list 等动态集成员被跳过，确认③）").isEmpty();

        // ⑥ 菜单绑定=预设码投影 + 祖先目录补全（AMB-2）；预设外菜单不绑
        assertThat(menuBound(opsRoleId, "system:user:list")).as("ops_admin 绑用户菜单叶").isTrue();
        assertThat(menuBound(opsRoleId, "system:user:create")).as("ops_admin 绑用户创建 BUTTON").isTrue();
        assertThat(menuBound(viewerRoleId, "system:role:list")).as("viewer 绑角色菜单叶").isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id
                where rm.role_id = ? and m.menu_type = 'MENU'
                  and m.permission_code = 'system:role:list'
                """, Long.class, opsRoleId))
                .as("ops_admin 不绑 viewer 域菜单").isZero();
        Long usersMenuId = jdbcTemplate.queryForObject(
                "select id from sys_menu where menu_type = 'MENU' and permission_code = 'system:user:list'",
                Long.class);
        Long usersMenuParentId = jdbcTemplate.queryForObject(
                "select parent_id from sys_menu where id = ?", Long.class, usersMenuId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu where role_id = ? and menu_id = ?",
                Long.class, opsRoleId, usersMenuParentId))
                .as("祖先目录随投影补全（grantableMenus 同构）").isEqualTo(1L);

        // ⑦ 写配置（sys_config 租户域：平台缺省基座上按模板覆盖，确认②③）
        Map<String, Object> config = jdbcTemplate.queryForMap(
                "select watermark_enabled, captcha_enabled, lock_idle_timeout_ms, footer_copyright, "
                        + "footer_visible, watermark_template from sys_config where tenant_id = ?",
                tenantCode);
        assertThat(Boolean.TRUE.equals(config.get("watermark_enabled")))
                .as("watermarkEnabled=false 生效").isFalse();
        assertThat(Boolean.TRUE.equals(config.get("captcha_enabled")))
                .as("captchaEnabled=true 生效").isTrue();
        assertThat(((Number) config.get("lock_idle_timeout_ms")).intValue())
                .as("lockIdleTimeoutMs=60000 生效").isEqualTo(60000);
        assertThat(String.valueOf(config.get("footer_copyright"))).as("footerCopyright 生效")
                .isEqualTo("(c) TPL");
        assertThat(Boolean.TRUE.equals(config.get("footer_visible")))
                .as("footerVisible=true 生效").isTrue();
        assertThat(String.valueOf(config.get("watermark_template"))).as("watermarkTemplate 生效")
                .isEqualTo("{username}");

        // ⑧ 前缀优先（确认③「adminUsername 优先模板前缀」——模板命中有前缀时前缀胜过请求显式值；
        // 昵称=请求显式值优先）
        String explicitCode = FIXTURE_PREFIX + "-init-e";
        createTemplateOk(FIXTURE_PREFIX + "-init-tpl2", "开通模板二", buildFullContent(), null);
        JsonNode explicit = callAsSuperAdmin(post(TENANTS_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBodyWithTemplate(explicitCode,
                        FIXTURE_PREFIX + "-init-tpl2", "explicit_admin", "显式管理员"))));
        assertThat(explicit.path("success").asBoolean()).as("显式管理员开通应成功：%s", explicit).isTrue();
        assertThat(explicit.path("data").path("adminUsername").asText())
                .as("模板前缀优先于请求显式 adminUsername（确认③字面口径）")
                .isEqualTo("tpladm" + explicitCode);
        assertThat(explicit.path("data").path("adminNickname").asText())
                .as("请求显式 adminNickname 优先于模板昵称（契约未定昵称优先级——显式值优先）")
                .isEqualTo("显式管理员");
    }

    private List<String> permissionCodesOfRole(Long roleId) {
        return jdbcTemplate.queryForList("""
                select p.permission_code from sys_role_permission rp
                join sys_permission p on p.id = rp.permission_id
                where rp.role_id = ? order by p.permission_code
                """, String.class, roleId);
    }

    private boolean menuBound(Long roleId, String permissionCode) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id
                where rm.role_id = ? and m.permission_code = ?
                """, Long.class, roleId, permissionCode);
        return count != null && count > 0;
    }

    // ---------- AC-4（REQ-20261125-230807 确认③/AC-22 更新）：templateCode 缺省/blank →
    // PARAM_INVALID 族拒绝且不落任何开通产物；非空未命中 → 既有兜底动态授权保留
    // （REQ-20261122-233426 确认③「未命中=现行为逐字不变」；TenantInitUseCase.resolveTemplate
    // 查无此码→null 走动态授权，零回归） ----------

    @Test
    void should_reject_init_without_template_and_keep_dynamic_grant_when_unmatched() throws Exception {
        // ① 未传 templateCode（字段缺省=null）：HTTP 面 bean 校验拦截（@NotBlank，blank→PARAM_INVALID 族；
        //    拒绝口径对齐 REQ20261125230807TenantInitTemplateRequiredIntegrationTest）
        String blankCode = FIXTURE_PREFIX + "-compat-a";
        JsonNode blankResult = callAsSuperAdmin(post(TENANTS_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBodyWithTemplate(blankCode, null,
                        blankCode.replace('-', '_') + "_admin", blankCode + " 管理员"))));
        assertThat(blankResult.path("success").asBoolean())
                .as("templateCode 缺省开通应被拒绝（必填化确认③）：%s", blankResult).isFalse();
        assertThat(blankResult.path("code").asText())
                .as("拒绝走统一响应体 Result.code≠0（PARAM_INVALID 族，AMB-15 口径）").isNotEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant where tenant_code = ?", Long.class, blankCode))
                .as("校验拒绝后不应落租户（拒绝即拦截）").isZero();

        // ② 未命中 templateCode（非空、查无此码）：兜底现行为保留（动态授权集逐字不变，AMB-3）
        String missedCode = FIXTURE_PREFIX + "-compat-b";
        JsonNode missedView = callAsSuperAdmin(post(TENANTS_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBodyWithTemplate(missedCode, FIXTURE_PREFIX + "-no-such",
                        missedCode.replace('-', '_') + "_admin", missedCode + " 管理员"))));
        assertThat(missedView.path("success").asBoolean())
                .as("非空未命中模板应保留兜底开通成功（兼容零回归）：%s", missedView).isTrue();
        // 兜底路径 adminUsername=请求原值（无模板前缀改写）——TesterTest 冗余基线删除后的保留锚
        assertThat(missedView.path("data").path("adminUsername").asText())
                .as("未命中兜底 adminUsername=请求原值").isEqualTo(missedCode.replace('-', '_') + "_admin");
        assertDynamicGrantSurface(missedCode);
    }

    /** 动态授权集既有面断言（非空未命中兜底路径）：tenant_admin 角色 + 非保留授权 + 保留域排除（含 V32 新点）。 */
    private void assertDynamicGrantSurface(String tenantCode) {
        List<Long> roleIds = jdbcTemplate.queryForList(
                "select id from sys_role where tenant_id = ? and role_code = 'tenant_admin'",
                Long.class, tenantCode);
        assertThat(roleIds).as("未传模板仍建 tenant_admin 默认角色（现行为）").hasSize(1);
        Long roleId = roleIds.get(0);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_role_permission rp
                join sys_permission p on p.id = rp.permission_id
                where rp.role_id = ? and p.permission_code = 'system:user:list'
                """, Long.class, roleId)).as("非保留权限照常授予（动态全量）").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForList("""
                select p.permission_code from sys_role_permission rp
                join sys_permission p on p.id = rp.permission_id
                where rp.role_id = ? and (p.permission_code like 'system:tenant:%'
                       or p.permission_code like 'system:tenant-template:%'
                       or p.permission_code like 'system:tenant-package:%'
                       or p.permission_code like 'infra:audit:%')
                """, String.class, roleId))
                .as("保留域不授（含 V32 新点 system:tenant-template:*/usage-overview，零回归+零增量）")
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id and m.menu_type = 'BUTTON'
                where rm.role_id = ? and m.permission_code = 'system:user:create'
                """, Long.class, roleId)).as("BUTTON 节点照常投影（现行为）").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_config where tenant_id = ?", Long.class, tenantCode))
                .as("无模板不写 sys_config（无配置行按缺省生效，既有口径）").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where tenant_code = ?", Long.class, tenantCode))
                .as("兜底路径无默认套餐绑定（现行为）").isNull();
    }

    private String initTenantOk(String tenantCode, String templateCode) throws Exception {
        JsonNode result = callAsSuperAdmin(post(TENANTS_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBodyWithTemplate(tenantCode, templateCode,
                        tenantCode.replace('-', '_') + "_admin", tenantCode + " 管理员"))));
        assertThat(result.path("success").asBoolean()).as("init 开通应成功：%s", result).isTrue();
        return tenantCode;
    }

    // ---------- AC-5：usage-overview 跨租户旁路聚合 ----------

    @Test
    void should_aggregate_usage_overview_bypass_when_queried() throws Exception {
        bootstrapSysFile();
        Instant now = Instant.now();

        // ① 五租户：default（seed，启用）+ 普通开通 + 停用 + 宽限 + 过期
        // REQ-20261125-230807 确认③：templateCode 必填化后开通夹具统一走模板路径（原 setup 无模板 init
        // 被 bean 校验拦截的级联修复；defaultPackageId=null 不改套餐分布断言口径，模板路径同样建 1 管理员）
        String ovTemplateCode = FIXTURE_PREFIX + "-ov-tpl";
        createTemplateOk(ovTemplateCode, "用量总览开通模板", buildFullContent(), null);
        String plainCode = initTenantOk(FIXTURE_PREFIX + "-ov-plain", ovTemplateCode);
        String disabledCode = initTenantOk(FIXTURE_PREFIX + "-ov-disabled", ovTemplateCode);
        String graceCode = initTenantOk(FIXTURE_PREFIX + "-ov-grace", ovTemplateCode);
        String expiredCode = initTenantOk(FIXTURE_PREFIX + "-ov-expired", ovTemplateCode);
        jdbcTemplate.update("update sys_tenant set status = 'DISABLED' where tenant_code = ?", disabledCode);
        jdbcTemplate.update(
                "update sys_tenant set expires_at = ?, grace_until = ? where tenant_code = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(1))),
                java.sql.Timestamp.from(now.plus(Duration.ofDays(1))), graceCode);
        jdbcTemplate.update("update sys_tenant set expires_at = ? where tenant_code = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(2))), expiredCode);

        // ② 用量数据：plain 未删 100+200（已删 500 不计）+ 2 业务用户；grace 999
        seedUserInTenant(FIXTURE_PREFIX + "-ov-u1", plainCode);
        seedUserInTenant(FIXTURE_PREFIX + "-ov-u2", plainCode);
        insertSysFile(plainCode, 200L, false);
        insertSysFile(plainCode, 100L, false);
        insertSysFile(plainCode, 500L, true);
        insertSysFile(graceCode, 999L, false);

        // ③ 套餐分布夹具：plain 绑套餐（其余 4 租户未绑定 → null 项聚合，TL 裁决④）
        long ovPackageId = createPackageOk(FIXTURE_PREFIX + "-ov-pkg");
        jdbcTemplate.update("update sys_tenant set package_id = ? where tenant_code = ?",
                ovPackageId, plainCode);

        // ④ 聚合（操作者处于 default 上下文 → 跨租户旁路正确，确认⑥）
        JsonNode overview = callAsSuperAdmin(get(OVERVIEW_URL));
        assertThat(overview.path("success").asBoolean()).as("usage-overview 应成功：%s", overview).isTrue();
        JsonNode data = overview.path("data");
        assertThat(data.path("totalTenants").asLong()).as("totalTenants=目录全量口径").isEqualTo(5L);

        JsonNode statusCounts = data.path("statusCounts");
        assertThat(statusCounts.path("enabled").asLong()).as("四态计数-启用中").isEqualTo(2L);
        assertThat(statusCounts.path("grace").asLong()).as("四态计数-宽限期").isEqualTo(1L);
        assertThat(statusCounts.path("expired").asLong()).as("四态计数-已过期").isEqualTo(1L);
        assertThat(statusCounts.path("disabled").asLong()).as("四态计数-已停用（正交优先）").isEqualTo(1L);

        // ⑤ 套餐分布（TL 裁决④补列：未绑定聚合为 null 项 + tenantCount 降序，msm 口径）
        JsonNode distribution = data.path("packageDistribution");
        assertThat(distribution.isArray()).as("packageDistribution 数组（可选列，TL 裁决④补列）").isTrue();
        assertThat(distribution.size()).as("分布项数=null 项+1 绑定项").isEqualTo(2);
        assertThat(distribution.get(0).path("packageId").isNull() || distribution.get(0).path("packageId")
                .isMissingNode()).as("未绑定聚合项 packageId=null（tenantCount 降序居首）").isTrue();
        assertThat(distribution.get(0).path("tenantCount").asLong()).as("未绑定聚合租户数").isEqualTo(4L);
        assertThat(distribution.get(1).path("packageId").asLong()).as("绑定项 packageId").isEqualTo(ovPackageId);
        assertThat(distribution.get(1).path("packageName").asText()).as("绑定项套餐名实时冗余")
                .isEqualTo(FIXTURE_PREFIX + "-ov-pkg");
        assertThat(distribution.get(1).path("tenantCount").asLong()).as("绑定项租户数").isEqualTo(1L);

        JsonNode usages = data.path("tenantUsages");
        assertThat(usages.isArray()).as("tenantUsages 数组").isTrue();
        assertThat(usages.size()).as("topN=20 内全量租户（5<20）").isEqualTo(5);
        long previousBytes = Long.MAX_VALUE;
        boolean plainSeen = false;
        for (JsonNode usage : usages) {
            long bytes = usage.path("storageBytes").asLong();
            assertThat(bytes).as("按 storageBytes 降序").isLessThanOrEqualTo(previousBytes);
            previousBytes = bytes;
            assertThat(usage.path("tenantId").asText()).as("wire 形状（AMB-4 字段集）").isNotEmpty();
            assertThat(usage.has("quotaBytes")).as("quotaBytes 字段在位").isTrue();
            assertThat(usage.has("quotaSource")).as("quotaSource 字段在位").isTrue();
            assertThat(usage.has("userCount")).as("userCount 字段在位").isTrue();
            assertThat(usage.path("status").asText()).as("派生四视觉态词汇")
                    .isIn("ENABLED", "GRACE", "EXPIRED", "DISABLED");
            if (plainCode.equals(usage.path("tenantCode").asText())) {
                plainSeen = true;
                assertThat(bytes).as("plain storageBytes=未删 100+200").isEqualTo(300L);
                assertThat(usage.path("userCount").asLong())
                        .as("plain userCount=管理员+2").isEqualTo(3L);
                assertThat(usage.path("status").asText()).isEqualTo("ENABLED");
            }
            if (graceCode.equals(usage.path("tenantCode").asText())) {
                assertThat(bytes).as("grace storageBytes=999").isEqualTo(999L);
                assertThat(usage.path("status").asText()).as("宽限期派生").isEqualTo("GRACE");
            }
            if (disabledCode.equals(usage.path("tenantCode").asText())) {
                assertThat(usage.path("status").asText()).as("停用正交优先").isEqualTo("DISABLED");
            }
            if (expiredCode.equals(usage.path("tenantCode").asText())) {
                assertThat(usage.path("status").asText()).as("宽限也过派生过期").isEqualTo("EXPIRED");
            }
        }
        assertThat(plainSeen).as("旁路聚合应含夹具租户（操作者 default 上下文不限定）").isTrue();

        // ⑥ 权限点面：端点挂新种子点（确认⑤⑥；RequirePermission 标注口径，SystemApiSurfaceTest 先例）
        try {
            var method = UsageOverviewController.class.getMethod("usageOverview");
            var annotation = method.getAnnotation(com.acme.framework.web.security.RequirePermission.class);
            assertThat(annotation).as("usage-overview 端点须挂权限标注").isNotNull();
            assertThat(annotation.value()).isEqualTo(SystemPermissions.TENANT_USAGE_OVERVIEW);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------- 模板端点权限标注面（确认④⑤：四端点挂新种子点） ----------

    @Test
    void should_annotate_template_endpoints_with_seeded_permissions() throws Exception {
        assertThat(annotationOf(AdminTenantInitTemplateController.class, "list",
                Integer.class, Integer.class, String.class).value())
                .isEqualTo(SystemPermissions.TENANT_TEMPLATE_LIST);
        assertThat(annotationOf(AdminTenantInitTemplateController.class, "create",
                com.acme.platform.system.api.dto.AdminTenantInitTemplateCreate.class).value())
                .isEqualTo(SystemPermissions.TENANT_TEMPLATE_CREATE);
        assertThat(annotationOf(AdminTenantInitTemplateController.class, "update",
                Long.class, com.acme.platform.system.api.dto.AdminTenantInitTemplateUpdate.class).value())
                .isEqualTo(SystemPermissions.TENANT_TEMPLATE_UPDATE);
        assertThat(annotationOf(AdminTenantInitTemplateController.class, "delete", Long.class).value())
                .isEqualTo(SystemPermissions.TENANT_TEMPLATE_DELETE);
    }

    private com.acme.framework.web.security.RequirePermission annotationOf(
            Class<?> controller, String method, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        com.acme.framework.web.security.RequirePermission annotation =
                controller.getMethod(method, parameterTypes).getAnnotation(
                        com.acme.framework.web.security.RequirePermission.class);
        assertThat(annotation).as("%s#%s 须挂权限标注", controller.getSimpleName(), method).isNotNull();
        return annotation;
    }

    // ---------- 夹具装配 ----------

    /** 模板 content（CRUD 往返口径：双预设 + 两配置项；不含默认套餐——由 templateBody 携带）。 */
    private static ObjectNode buildContent() {
        ObjectNode content = JSON.createObjectNode();
        content.put("adminUsernamePrefix", "tpladm");
        content.put("adminNickname", "模板管理员");
        ArrayNode presets = content.putArray("rolePresets");
        presets.add(presetNode("ops_admin", "运营管理员", "system:user:list", "system:user:create"));
        presets.add(presetNode("viewer", "只读角色", "system:role:list"));
        ArrayNode entries = content.putArray("configEntries");
        entries.add(configEntryNode("watermarkEnabled", "false"));
        entries.add(configEntryNode("lockIdleTimeoutMs", "60000"));
        return content;
    }

    /** 模板 content（init 闭环口径：六键配置全集）。 */
    private static ObjectNode buildFullContent() {
        ObjectNode content = JSON.createObjectNode();
        content.put("adminUsernamePrefix", "tpladm");
        content.put("adminNickname", "模板管理员");
        ArrayNode presets = content.putArray("rolePresets");
        presets.add(presetNode("ops_admin", "运营管理员", "system:user:list", "system:user:create"));
        presets.add(presetNode("viewer", "只读角色", "system:role:list"));
        ArrayNode entries = content.putArray("configEntries");
        entries.add(configEntryNode("watermarkEnabled", "false"));
        entries.add(configEntryNode("captchaEnabled", "true"));
        entries.add(configEntryNode("lockIdleTimeoutMs", "60000"));
        entries.add(configEntryNode("footerCopyright", "(c) TPL"));
        entries.add(configEntryNode("footerVisible", "true"));
        entries.add(configEntryNode("watermarkTemplate", "{username}"));
        return content;
    }

    private static ObjectNode presetNode(String roleKey, String roleName, String... codes) {
        ObjectNode preset = JSON.createObjectNode();
        preset.put("roleKey", roleKey);
        preset.put("roleName", roleName);
        ArrayNode array = preset.putArray("permissionCodes");
        for (String code : codes) {
            array.add(code);
        }
        return preset;
    }

    private static ObjectNode configEntryNode(String configKey, String value) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("configKey", configKey);
        entry.put("value", value);
        return entry;
    }

    private static ObjectNode templateBody(String code, String name, JsonNode content, Long packageId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("code", code);
        body.put("name", name);
        body.putNull("remark");
        body.set("content", content);
        if (packageId == null) {
            body.putNull("defaultPackageId");
        } else {
            body.put("defaultPackageId", packageId);
        }
        return body;
    }

    private void createTemplateOk(String code, String name, ObjectNode content, Long packageId)
            throws Exception {
        JsonNode result = callAsSuperAdmin(post(TEMPLATES_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(templateBody(code, name, content, packageId))));
        assertThat(result.path("success").asBoolean()).as("创建模板应成功：%s", result).isTrue();
    }

    private static ObjectNode initBodyWithTemplate(String tenantCode, String templateCode,
                                                   String adminUsername, String adminNickname) {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 开通夹具租户");
        body.putNull("remark");
        if (adminUsername == null) {
            body.putNull("adminUsername");
        } else {
            body.put("adminUsername", adminUsername);
        }
        if (adminNickname == null) {
            body.putNull("adminNickname");
        } else {
            body.put("adminNickname", adminNickname);
        }
        body.putNull("parentId");
        if (templateCode == null) {
            body.putNull("templateCode");
        } else {
            body.put("templateCode", templateCode);
        }
        return body;
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

    /** system IT 库无 sys_file（platform-infra 迁移域，TENANT-4 AMB-5 同款）：以 infra V2 同列集自举。 */
    private void bootstrapSysFile() {
        jdbcTemplate.execute("""
                create table if not exists sys_file (
                    id            bigint PRIMARY KEY,
                    bucket        varchar(64)  NOT NULL,
                    object_key    varchar(512) NOT NULL,
                    original_name varchar(255) NOT NULL,
                    size          bigint       NOT NULL,
                    content_type  varchar(128),
                    biz_type      varchar(64),
                    biz_id        varchar(64),
                    tenant_id     varchar(64)  NOT NULL,
                    uploaded_by   varchar(64),
                    created_at    timestamptz  NOT NULL,
                    created_by    varchar(64)  NOT NULL,
                    updated_at    timestamptz  NOT NULL,
                    updated_by    varchar(64)  NOT NULL,
                    version       bigint       NOT NULL,
                    deleted       boolean      NOT NULL
                )""");
    }

    private void insertSysFile(String tenantCode, long size, boolean deleted) {
        jdbcTemplate.update("""
                        insert into sys_file (id, bucket, object_key, original_name, size, tenant_id,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, 'req-it', ?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
                        """,
                Math.abs(System.identityHashCode(tenantCode + size + deleted)) & 0x7FFFFFFFL | 0x700000000L,
                tenantCode + "/" + size + "/" + deleted, "f-" + size, size, tenantCode,
                OPERATOR, OPERATOR, deleted);
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

    // ---------- HTTP 支撑（REQ-150802 先例口径） ----------

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return bodyOf(mvc.perform(request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", token())
                .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "dev-1", "1.0"))).andReturn());
    }

    private void callAsSuperAdminOk(MockHttpServletRequestBuilder request, String message) throws Exception {
        MvcResult result = mvc.perform(request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", token())
                .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "dev-1", "1.0"))).andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (!content.isBlank()) {
            assertThat(JSON.readTree(content).path("success").asBoolean()).as(message).isTrue();
        } else {
            assertThat(result.getResponse().getStatus())
                    .as(message + "（void 端点空体 2xx 惯例）")
                    .isBetween(200, 299);
        }
    }

    private String token() {
        return tokenIssuer.issue(new AuthenticatedSubject("1", "default",
                com.acme.common.context.Channel.B, Set.of("super_admin")));
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
