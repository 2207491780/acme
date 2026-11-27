package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.SystemErrorCodes;
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
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261121-022910 租户套餐模型集成测试（Testcontainers PG，装配口径照
 * {@code SystemIntegrationTest}/{@code REQ20261120214653PermissionFilterApiIntegrationTest}；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-1~11（§确认记录②~⑦、§API）：</p>
 * <ul>
 *   <li>AC-1~4：套餐 CRUD 五端点 + 业务错误码五枚（TENANT_PACKAGE_DUPLICATE / NOT_FOUND /
 *       IN_USE / MENU_INVALID / MENU_FORBIDDEN——wire 码断言引用 SystemErrorCodes 常量，
 *       码名契约钉死、字面量段位不猜测）；业务失败一律 Result 信封 code≠0，HTTP 状态对齐
 *       框架翻译器现状不断言具体 4xx（MessageApi 集成测试先例口径）。</li>
 *   <li>AC-5~8：租户绑定/解绑（PUT tenants 增可选 packageId）+ /me 菜单树过滤（确认②语义：
 *       交集 + 祖先链自动补全 + BUTTON 随叶级联 + 读时实时）+ 基线回归（package_id NULL 行为不变）。</li>
 *   <li>AC-9：绑定套餐租户的角色-菜单分配越界拒绝（确认⑤）；历史越界绑定读时被过滤在 AC-6 承载。</li>
 *   <li>AC-10：无 system:tenant-package:* 权限 → 403（既有 RBAC 翻译器口径，PERMISSION_DENIED
 *       登记 403，SystemApiSurfaceTest 同款断言面）；无权限装配照 MessageApi 集成测试未绑定角色令牌先例。</li>
 *   <li>AC-11：V24 种子形状（菜单 46~49 / role_menu 46~49 / 权限 69~72）+ 种子重放幂等
 *       （SystemIntegrationTest.seedScriptReplayIsIdempotent 同款 INSERT 重放口径，DDL 不在范围）。</li>
 * </ul>
 *
 * <p>夹具口径（PermissionFilterApiIntegrationTest 先例）：每用例以 SEQ 唯一 tag 建独立租户/角色/用户
 * 夹具行（id 自 922022910000 起，远大于种子与雪花撞车区间），@AfterEach 按 fixture 租户码物理清理，
 * 套餐表整表清理（本容器内无种子行，用例是唯一写入方）。断言值全部由契约语义 + 种子锚点推导。</p>
 *
 * <p>种子锚点（V20/V22 留档）：1=系统管理目录、2=用户管理叶、13=创建用户 BUTTON（父 2）、
 * 40=部门管理叶（父 1）、30~32=部门 BUTTON（父 40）。</p>
 *
 * <p>依赖（映射表注明，backend 工人并行交付）：V24 迁移（表/列/种子）、
 * /admin/v1/system/tenant-packages 五端点、SystemErrorCodes 五常量、MeUseCase 套餐过滤接线、
 * RoleUseCase 套餐范围校验、AdminTenantUpdate.packageId。实现未落盘时本类编译失败/断言红灯 =
 * 疑点非用例缺陷（tester 交付口径：附原样输出归因 TL）。</p>
 */
class REQ20261121022910TenantPackageApiIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req022910-it";

    /** 夹具行 id 基点（种子权限/菜单 id ≤72、雪花 id 远大于此，teardown 按夹具租户码清理）。 */
    private static final long ID_BASE = 922_022_910_000L;

    private static final long UNKNOWN_MENU_ID = 999_999_999_999L;

    // ---- 种子菜单锚点（V20/V22 留档，REQ20261120213603MenuFilterIntegrationTest 同款口径） ----
    private static final long SEED_DIR_SYSTEM = 1L;
    private static final long SEED_USERS_LEAF = 2L;
    private static final long SEED_CREATE_USER_BTN = 13L;
    private static final long SEED_DEPTS_LEAF = 40L;
    private static final long SEED_DEPTS_BTN_FIRST = 30L;

    private static final String LIST_URL = "/admin/v1/system/tenant-packages";

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

    /** 每用例唯一 tag（ASCII 小写）：锚定套餐名 keyword 域与夹具租户/角色/用户编码，互不串扰。 */
    private String tag;
    private long fixtureSeq;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        tag = "req022910t" + SEQ.incrementAndGet();
    }

    @AfterEach
    void cleanUp() {
        // 夹具脏数据 teardown：按夹具租户码清绑定行与目录行；套餐表整表清理（本容器无种子行）
        jdbcTemplate.update("delete from sys_tenant_package_menu");
        jdbcTemplate.update("delete from sys_tenant_package");
        jdbcTemplate.update("delete from sys_role_menu where tenant_id = ?", fixtureTenantCode());
        jdbcTemplate.update("delete from sys_user_role where tenant_id = ?", fixtureTenantCode());
        jdbcTemplate.update("delete from sys_user where tenant_id = ?", fixtureTenantCode());
        jdbcTemplate.update("delete from sys_role where tenant_id = ?", fixtureTenantCode());
        jdbcTemplate.update("delete from sys_tenant where tenant_code = ?", fixtureTenantCode());
        TenantContext.clear();
        UserContext.clear();
    }

    private String fixtureTenantCode() {
        return tag + "-tenant";
    }

    // ---------- 夹具装配（JdbcTemplate 直插，PermissionFilterApi 先例口径） ----------

    /** 建夹具租户/角色/用户并绑定：角色勾选 {用户管理叶 2, 创建用户按钮 13, 部门管理叶 40}
     *  （不含目录 1——祖先链由树组装补全；不含部门按钮 30~32——按钮级联语义的净锚点）。 */
    private FixtureTenant seedTenantWithRoleBoundToMenus() {
        fixtureSeq += 1;
        long tenantId = ID_BASE + fixtureSeq * 10;
        long roleId = ID_BASE + fixtureSeq * 10 + 1;
        long userId = ID_BASE + fixtureSeq * 10 + 2;
        long userRoleId = ID_BASE + fixtureSeq * 10 + 3;
        String tenantCode = fixtureTenantCode();
        String roleCode = tag + "_role";
        jdbcTemplate.update("""
                insert into sys_tenant (id, tenant_code, tenant_name, status,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'ENABLED', now(), ?, now(), ?, 0, false)
                """, tenantId, tenantCode, tag + "夹具租户", OPERATOR, OPERATOR);
        jdbcTemplate.update("""
                insert into sys_role (id, tenant_id, role_code, role_name, status, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, 'ENABLED', false, now(), ?, now(), ?, 0, false)
                """, roleId, tenantCode, roleCode, tag + "夹具角色", OPERATOR, OPERATOR);
        jdbcTemplate.update("""
                insert into sys_user (id, tenant_id, username, nickname, status, built_in,
                                      created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, 'ENABLED', false, now(), ?, now(), ?, 0, false)
                """, userId, tenantCode, tag + "_user", tag + "夹具用户", OPERATOR, OPERATOR);
        jdbcTemplate.update("""
                insert into sys_user_role (id, tenant_id, user_id, role_id,
                                           created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, now(), ?, now(), ?, 0, false)
                """, userRoleId, tenantCode, userId, roleId, OPERATOR, OPERATOR);
        bindRoleMenus(tenantCode, roleId, List.of(SEED_USERS_LEAF, SEED_CREATE_USER_BTN, SEED_DEPTS_LEAF));
        return new FixtureTenant(tenantId, tenantCode, roleId, roleCode, userId);
    }

    private void bindRoleMenus(String tenantCode, long roleId, List<Long> menuIds) {
        long base = ID_BASE + fixtureSeq * 10 + 4;
        int i = 0;
        for (Long menuId : menuIds) {
            jdbcTemplate.update("""
                    insert into sys_role_menu (id, tenant_id, role_id, menu_id,
                                               created_at, created_by, updated_at, updated_by, version, deleted)
                    values (?, ?, ?, ?, now(), ?, now(), ?, 0, false)
                    """, base + i++, tenantCode, roleId, menuId, OPERATOR, OPERATOR);
        }
    }

    private record FixtureTenant(long tenantId, String tenantCode, long roleId, String roleCode, long userId) {
    }

    // ---------- HTTP 支撑 ----------

    private String token(String userId, String tenant, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, tenant, com.acme.common.context.Channel.B, roles));
    }

    private MvcResult performAs(String userId, String tenant, Set<String> roles,
                                MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", token(userId, tenant, roles))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    /** 平台管理操作者：种子超管（userId=1 / default 租户 / super_admin 短路全量权限点）。 */
    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return bodyOf(performAs("1", "default", Set.of("super_admin"), request));
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static ObjectNode packageBody(String name, String remark, List<Long> menuIds) {
        ObjectNode node = JSON.createObjectNode();
        node.put("packageName", name);
        if (remark != null) {
            node.put("remark", remark);
        }
        var array = node.putArray("menuIds");
        menuIds.forEach(array::add);
        return node;
    }

    /** 创建套餐并断言成功信封，返回套餐 id（数值/字符串 wire 双兼容经 asLong 归一）。 */
    private long createPackageOk(String name, String remark, List<Long> menuIds) throws Exception {
        JsonNode body = callAsSuperAdmin(post(LIST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(packageBody(name, remark, menuIds))));
        assertThat(body.path("success").asBoolean()).as("创建套餐应成功：%s", body).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");
        return body.path("data").path("id").asLong();
    }

    private JsonNode packageDetail(long packageId) throws Exception {
        return callAsSuperAdmin(get(LIST_URL + "/" + packageId));
    }

    /** 更新套餐（乐观锁：先取详情 version 随体提交——框架 update 惯例 AdminTenantPackageUpdate
     *  @NotNull version；menuIds 全量替换）。 */
    private JsonNode updatePackage(long packageId, String name, String remark, List<Long> menuIds) throws Exception {
        JsonNode current = packageDetail(packageId);
        assertThat(current.path("success").asBoolean()).as("套餐详情应存在：%s", current).isTrue();
        ObjectNode body = packageBody(name, remark, menuIds);
        body.put("version", current.path("data").path("version").asLong());
        return callAsSuperAdmin(put(LIST_URL + "/" + packageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private JsonNode packagePage(String keyword) throws Exception {
        MockHttpServletRequestBuilder request = get(LIST_URL).queryParam("page", "1").queryParam("size", "50");
        if (keyword != null) {
            request.queryParam("keyword", keyword);
        }
        return callAsSuperAdmin(request);
    }

    private List<Long> roleMenuIdsOf(long roleId, String tenantCode) {
        return jdbcTemplate.queryForList(
                "select menu_id from sys_role_menu where role_id = ? and tenant_id = ? order by menu_id",
                Long.class, roleId, tenantCode);
    }

    private List<Long> meMenuIds(FixtureTenant fixture) throws Exception {
        MvcResult result = performAs(String.valueOf(fixture.userId()), fixture.tenantCode(),
                Set.of(fixture.roleCode()), get("/admin/v1/system/me"));
        assertThat(result.getResponse().getStatus()).as("/me 应可访问（认证即可）").isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("success").asBoolean()).as("/me 信封应成功：%s", body).isTrue();
        List<Long> ids = new ArrayList<>();
        collectMenuIds(body.path("data").path("menus"), ids);
        return ids;
    }

    private static void collectMenuIds(JsonNode nodes, List<Long> into) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            into.add(node.path("id").asLong());
            collectMenuIds(node.path("children"), into);
        }
    }

    /** 租户更新（packageId 可空：null=解除绑定）；先 GET 取乐观锁 version。 */
    private JsonNode putTenantPackage(long tenantId, Long packageId) throws Exception {
        JsonNode current = callAsSuperAdmin(get("/admin/v1/system/tenants/" + tenantId));
        assertThat(current.path("success").asBoolean()).as("租户详情应成功").isTrue();
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantName", current.path("data").path("tenantName").asText());
        body.put("version", current.path("data").path("version").asLong());
        if (packageId == null) {
            body.putNull("packageId");
        } else {
            body.put("packageId", packageId);
        }
        return callAsSuperAdmin(put("/admin/v1/system/tenants/" + tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    // ---------- AC-1：合法套餐 → 创建/分页/详情命中且 menuCount/tenantCount 正确 ----------

    @Test
    void should_create_package_and_expose_in_page_and_detail() throws Exception {
        long packageId = createPackageOk(tag + "基础套餐", "集成样例备注", List.of(SEED_USERS_LEAF));

        // 详情：menuIds 回读 + menuCount=1 / tenantCount=0（未被租户绑定）
        JsonNode detail = packageDetail(packageId);
        assertThat(detail.path("success").asBoolean()).isTrue();
        assertThat(detail.path("data").path("packageName").asText()).isEqualTo(tag + "基础套餐");
        assertThat(detail.path("data").path("menuCount").asLong()).isEqualTo(1L);
        assertThat(detail.path("data").path("tenantCount").asLong()).isZero();
        List<Long> detailMenuIds = new ArrayList<>();
        detail.path("data").path("menuIds").forEach(n -> detailMenuIds.add(n.asLong()));
        assertThat(detailMenuIds).containsExactly(SEED_USERS_LEAF);

        // 分页：keyword（packageName 模糊，契约钉死）命中且计数正确
        JsonNode page = packagePage(tag);
        assertThat(page.path("success").asBoolean()).isTrue();
        assertThat(page.path("data").path("total").asLong()).isEqualTo(1L);
        assertThat(page.path("data").path("records").size()).isEqualTo(1);
        assertThat(page.path("data").path("records").get(0).path("id").asLong()).isEqualTo(packageId);
        assertThat(page.path("data").path("records").get(0).path("menuCount").asLong()).isEqualTo(1L);

        // keyword 无命中：空页正常返回（非报错）
        JsonNode miss = packagePage(tag + "-zzz-nohit");
        assertThat(miss.path("data").path("total").asLong()).isZero();
    }

    // ---------- AC-2：重名（创建/更新撞既有名）→ TENANT_PACKAGE_DUPLICATE 且数据不变 ----------

    @Test
    void should_reject_duplicate_package_name_when_create_or_update() throws Exception {
        long first = createPackageOk(tag + "重名套餐", null, List.of(SEED_USERS_LEAF));
        long second = createPackageOk(tag + "另一套餐", null, List.of(SEED_DEPTS_LEAF));

        // 创建撞既有名 → 业务失败信封（wire 码=SystemErrorCodes 契约常量；HTTP 对齐翻译器现状不断言）
        JsonNode dupCreate = callAsSuperAdmin(post(LIST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(packageBody(tag + "重名套餐", null, List.of()))));
        assertThat(dupCreate.path("success").asBoolean()).isFalse();
        assertThat(dupCreate.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_PACKAGE_DUPLICATE);

        // 更新撞另一套餐名 → 同码，且被更新套餐数据不变
        JsonNode dupUpdate = updatePackage(second, tag + "重名套餐", null, List.of(SEED_USERS_LEAF));
        assertThat(dupUpdate.path("success").asBoolean()).isFalse();
        assertThat(dupUpdate.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_PACKAGE_DUPLICATE);
        JsonNode detailAfter = packageDetail(second);
        assertThat(detailAfter.path("data").path("packageName").asText()).isEqualTo(tag + "另一套餐");
        assertThat(detailAfter.path("data").path("menuIds").size()).isEqualTo(1);

        // 数据不变：失败创建不落行（总量仍 2）
        assertThat(packagePage(tag).path("data").path("total").asLong()).isEqualTo(2L);
    }

    // ---------- AC-3：menuIds 含不存在菜单 → TENANT_PACKAGE_MENU_INVALID ----------

    @Test
    void should_reject_unknown_menu_ids_when_save_package() throws Exception {
        long totalBefore = packagePage(tag).path("data").path("total").asLong();

        // 创建：menuIds 含不存在菜单 → 拒绝且不落行
        JsonNode badCreate = callAsSuperAdmin(post(LIST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(
                        packageBody(tag + "未知菜单套餐", null, List.of(UNKNOWN_MENU_ID)))));
        assertThat(badCreate.path("success").asBoolean()).isFalse();
        assertThat(badCreate.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_PACKAGE_MENU_INVALID);
        assertThat(packagePage(tag).path("data").path("total").asLong()).isEqualTo(totalBefore);

        // 更新：同样拒绝且原绑定不变（menuIds 全量替换仅在成功时发生）
        long packageId = createPackageOk(tag + "合法套餐", null, List.of(SEED_USERS_LEAF));
        JsonNode badUpdate = updatePackage(packageId, tag + "合法套餐", null,
                List.of(SEED_DEPTS_LEAF, UNKNOWN_MENU_ID));
        assertThat(badUpdate.path("success").asBoolean()).isFalse();
        assertThat(badUpdate.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_PACKAGE_MENU_INVALID);
        JsonNode detail = packageDetail(packageId);
        List<Long> menuIds = new ArrayList<>();
        detail.path("data").path("menuIds").forEach(n -> menuIds.add(n.asLong()));
        assertThat(menuIds).containsExactly(SEED_USERS_LEAF);
    }

    // ---------- AC-4：被租户绑定删除拒绝且保留；未绑定删除成功且绑定行清除 ----------

    @Test
    void should_guard_delete_when_package_in_use_and_purge_bindings_when_free() throws Exception {
        FixtureTenant fixture = seedTenantWithRoleBoundToMenus();
        long boundPackage = createPackageOk(tag + "占用套餐", null, List.of(SEED_USERS_LEAF));
        JsonNode bind = putTenantPackage(fixture.tenantId(), boundPackage);
        assertThat(bind.path("success").asBoolean()).as("绑定套餐应成功：%s", bind).isTrue();

        // 被租户绑定 → TENANT_PACKAGE_IN_USE，主行与绑定行保留
        JsonNode rejected = callAsSuperAdmin(delete(LIST_URL + "/" + boundPackage));
        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_PACKAGE_IN_USE);
        assertThat(packageDetail(boundPackage).path("success").asBoolean())
                .as("占用套餐删除被拒后仍可查").isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package_menu where package_id = ?",
                Long.class, boundPackage)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where id = ?", Long.class, fixture.tenantId()))
                .as("租户绑定关系不受删除失败影响").isEqualTo(boundPackage);

        // 未绑定空套餐 → 删除成功（2xx），主行消失、绑定行清除、详情 NOT_FOUND
        long freePackage = createPackageOk(tag + "空闲套餐", null, List.of());
        MvcResult deleted = performAs("1", "default", Set.of("super_admin"),
                delete(LIST_URL + "/" + freePackage));
        assertThat(deleted.getResponse().getStatus())
                .as("未绑定套餐删除应成功（void 端点豁免信封或 2xx 信封均合规）")
                .isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package where id = ? and deleted = false",
                Long.class, freePackage)).as("主行软删除（AuditBase 逻辑删）：未删行清零").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package_menu where package_id = ?",
                Long.class, freePackage)).isZero();
        JsonNode gone = packageDetail(freePackage);
        assertThat(gone.path("success").asBoolean()).isFalse();
        assertThat(gone.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_PACKAGE_NOT_FOUND);
    }

    // ---------- AC-5：租户更新合法 packageId 绑定生效；非法 → TENANT_PACKAGE_NOT_FOUND ----------

    @Test
    void should_bind_and_unbind_package_when_tenant_updated() throws Exception {
        FixtureTenant fixture = seedTenantWithRoleBoundToMenus();
        long packageId = createPackageOk(tag + "绑定套餐", null, List.of(SEED_USERS_LEAF));

        // 绑定生效：租户行 package_id 落库 + 套餐 tenantCount=1
        JsonNode bound = putTenantPackage(fixture.tenantId(), packageId);
        assertThat(bound.path("success").asBoolean()).as("绑定应成功：%s", bound).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where id = ?", Long.class, fixture.tenantId()))
                .isEqualTo(packageId);
        assertThat(packageDetail(packageId).path("data").path("tenantCount").asLong()).isEqualTo(1L);

        // 解除绑定（packageId=null=不受限）：tenantCount 回零
        JsonNode unbound = putTenantPackage(fixture.tenantId(), null);
        assertThat(unbound.path("success").asBoolean()).as("解绑应成功：%s", unbound).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where id = ?", Long.class, fixture.tenantId()))
                .isNull();
        assertThat(packageDetail(packageId).path("data").path("tenantCount").asLong()).isZero();

        // 非法 packageId（不存在）→ TENANT_PACKAGE_NOT_FOUND，原绑定（NULL）不变
        JsonNode illegal = putTenantPackage(fixture.tenantId(), UNKNOWN_MENU_ID);
        assertThat(illegal.path("success").asBoolean()).isFalse();
        assertThat(illegal.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_PACKAGE_NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where id = ?", Long.class, fixture.tenantId()))
                .as("非法 packageId 提交不得改写绑定").isNull();
    }

    // ---------- AC-6：绑定租户 /me 菜单树 = 套餐交集 + 祖先补全 + BUTTON 级联 ----------

    @Test
    void should_filter_user_menu_tree_by_package_with_ancestor_completion() throws Exception {
        FixtureTenant fixture = seedTenantWithRoleBoundToMenus();
        // 套餐仅含「用户管理」叶（2）：不含父目录 1、不含按钮 13、不含角色勾选的部门叶 40
        long packageId = createPackageOk(tag + "过滤套餐", null, List.of(SEED_USERS_LEAF));
        JsonNode bind = putTenantPackage(fixture.tenantId(), packageId);
        assertThat(bind.path("success").asBoolean()).as("绑定应成功：%s", bind).isTrue();

        List<Long> visible = meMenuIds(fixture);

        // 祖先链自动补全（委派单钉死构造）：套餐含叶 2 而不含父目录 1 → 目录 1 自动出现
        assertThat(visible).as("套餐叶的父目录应自动补全").contains(SEED_DIR_SYSTEM);
        assertThat(visible).as("套餐内叶子应下发").contains(SEED_USERS_LEAF);
        // BUTTON 随所属叶子级联：角色勾选了按钮 13（创建用户，父=叶 2），套餐未单列 13 → 仍随叶下发
        assertThat(visible).as("套餐叶下 BUTTON 应随叶级联下发").contains(SEED_CREATE_USER_BTN);
        // 交集过滤：角色勾选的部门管理叶 40 在套餐外 → 历史越界绑定读时被过滤
        assertThat(visible).as("套餐外菜单即使角色已绑定也不得下发").doesNotContain(SEED_DEPTS_LEAF);
        // 套餐叶 40 不在包内 → 其按钮 30~32 亦不级联
        assertThat(visible).doesNotContain(SEED_DEPTS_BTN_FIRST);
        // 树完整渲染锚点前置（种子不变量）：叶 2 挂目录 1、按钮 13 挂叶 2
        assertThat(jdbcTemplate.queryForObject(
                "select parent_id from sys_menu where id = ?", Long.class, SEED_USERS_LEAF))
                .as("锚点前置：用户管理叶挂系统管理目录（种子不变量）").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select parent_id from sys_menu where id = ?", Long.class, SEED_CREATE_USER_BTN))
                .as("锚点前置：创建用户按钮挂用户管理叶（种子不变量）").isEqualTo(2L);
    }

    // ---------- AC-7：未绑定套餐租户行为与改造前完全一致（回归基线） ----------

    @Test
    void should_keep_baseline_menu_behavior_when_package_absent() throws Exception {
        FixtureTenant fixture = seedTenantWithRoleBoundToMenus();

        // ① /me 菜单树基线：角色绑定 {2,13,40} ∪ 树祖先补全 → {1,2,13,40}（改造前语义逐项全等）
        List<Long> visible = meMenuIds(fixture);
        assertThat(visible).containsExactlyInAnyOrder(
                SEED_DIR_SYSTEM, SEED_USERS_LEAF, SEED_CREATE_USER_BTN, SEED_DEPTS_LEAF);

        // ② 角色分配基线：无套餐租户分配任意菜单（目录 1）不触发套餐范围守卫 → 成功落库
        MvcResult assign = performAs("1", fixture.tenantCode(), Set.of("super_admin"),
                put("/admin/v1/system/roles/" + fixture.roleId() + "/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuIds\":[" + SEED_DIR_SYSTEM + "]}"));
        assertThat(assign.getResponse().getStatus())
                .as("无套餐租户的角色分配应成功（void 端点 2xx）").isEqualTo(200);
        assertThat(roleMenuIdsOf(fixture.roleId(), fixture.tenantCode()))
                .as("分配结果=请求闭包（叶+祖先）").containsExactlyInAnyOrder(SEED_DIR_SYSTEM);

        // ③ 默认租户（种子，未绑定套餐）/me 基线不受影响：既有菜单仍全量下发
        JsonNode superMe = callAsSuperAdmin(get("/admin/v1/system/me"));
        assertThat(superMe.path("success").asBoolean()).isTrue();
        List<Long> superMenus = new ArrayList<>();
        collectMenuIds(superMe.path("data").path("menus"), superMenus);
        assertThat(superMenus).as("无套餐租户（default）既有菜单树不受 V24 改造影响")
                .contains(SEED_DIR_SYSTEM, SEED_USERS_LEAF, SEED_CREATE_USER_BTN, SEED_DEPTS_LEAF);
    }

    // ---------- AC-8：套餐增删菜单对绑定租户即时生效（读时实时，无需重绑） ----------

    @Test
    void should_apply_package_change_immediately_for_bound_tenant() throws Exception {
        FixtureTenant fixture = seedTenantWithRoleBoundToMenus();
        // 角色补绑部门按钮 30（BUTTON 下发语义=角色勾选 ∩ 套餐叶级联：按钮随叶进入套餐集后，
        // 仍须角色已勾选才会出现在 /me 下发——与 AC-6 按钮 13 的级联语义同口径）
        jdbcTemplate.update("""
                insert into sys_role_menu (id, tenant_id, role_id, menu_id,
                                           created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, now(), ?, now(), ?, 0, false)
                """, ID_BASE + (fixtureSeq + 1) * 10 + 9, fixture.tenantCode(), fixture.roleId(),
                SEED_DEPTS_BTN_FIRST, OPERATOR, OPERATOR);
        long packageId = createPackageOk(tag + "即时套餐", null, List.of(SEED_USERS_LEAF));
        JsonNode bind = putTenantPackage(fixture.tenantId(), packageId);
        assertThat(bind.path("success").asBoolean()).isTrue();

        List<Long> before = meMenuIds(fixture);
        assertThat(before).contains(SEED_USERS_LEAF, SEED_CREATE_USER_BTN);
        assertThat(before).doesNotContain(SEED_DEPTS_LEAF, SEED_DEPTS_BTN_FIRST);

        // 套餐 menuIds 全量替换为部门管理叶（40）：不重绑租户、不重登 → 下次读即切换
        JsonNode updated = updatePackage(packageId, tag + "即时套餐", null, List.of(SEED_DEPTS_LEAF));
        assertThat(updated.path("success").asBoolean()).as("套餐更新应成功：%s", updated).isTrue();

        List<Long> after = meMenuIds(fixture);
        assertThat(after).as("套餐变更后旧菜单（含其级联按钮）应即时消失")
                .doesNotContain(SEED_USERS_LEAF, SEED_CREATE_USER_BTN);
        assertThat(after).as("套餐变更后新菜单应即时出现").contains(SEED_DEPTS_LEAF);
        assertThat(after).as("角色已勾选且随新套餐叶级联的按钮 30 应即时出现").contains(SEED_DEPTS_BTN_FIRST);
        assertThat(after).as("祖先目录仍自动补全").contains(SEED_DIR_SYSTEM);
    }

    // ---------- AC-9：绑定套餐租户的角色分配越界拒绝；套餐内成功 ----------

    @Test
    void should_guard_role_menu_assignment_within_package_scope() throws Exception {
        FixtureTenant fixture = seedTenantWithRoleBoundToMenus();
        long packageId = createPackageOk(tag + "范围套餐", null, List.of(SEED_USERS_LEAF));
        JsonNode bind = putTenantPackage(fixture.tenantId(), packageId);
        assertThat(bind.path("success").asBoolean()).isTrue();

        // 套餐内分配（用户管理叶 2）→ 成功（bindMenus 闭包补祖先 → {1,2}）
        MvcResult inScope = performAs("1", fixture.tenantCode(), Set.of("super_admin"),
                put("/admin/v1/system/roles/" + fixture.roleId() + "/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuIds\":[" + SEED_USERS_LEAF + "]}"));
        assertThat(inScope.getResponse().getStatus())
                .as("套餐内分配应成功（void 端点 2xx）").isEqualTo(200);
        assertThat(roleMenuIdsOf(fixture.roleId(), fixture.tenantCode()))
                .contains(SEED_USERS_LEAF)
                .as("绑定行仅含请求闭包（叶+祖先），无越界行")
                .allSatisfy(id -> assertThat(id).isIn(SEED_DIR_SYSTEM, SEED_USERS_LEAF));

        // 套餐外分配（部门管理叶 40）→ TENANT_PACKAGE_MENU_FORBIDDEN，绑定行不变
        // （操作者令牌租户=夹具租户：sys_role 租户过滤面要求上下文与角色行同租户）
        MvcResult outScope = performAs("1", fixture.tenantCode(), Set.of("super_admin"),
                put("/admin/v1/system/roles/" + fixture.roleId() + "/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuIds\":[" + SEED_DEPTS_LEAF + "]}"));
        JsonNode outBody = bodyOf(outScope);
        assertThat(outBody.path("success").asBoolean()).isFalse();
        assertThat(outBody.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_PACKAGE_MENU_FORBIDDEN);
        assertThat(roleMenuIdsOf(fixture.roleId(), fixture.tenantCode()))
                .as("越界分配整体拒绝，绑定行不变")
                .containsExactlyInAnyOrder(SEED_USERS_LEAF, SEED_DIR_SYSTEM);
    }

    // ---------- AC-10：无 system:tenant-package:* 权限访问五端点 → 403（既有 RBAC 口径） ----------

    @Test
    void should_reject_without_package_permissions_per_existing_rbac() throws Exception {
        // 无权限装配（MessageApi 集成测试先例）：令牌角色无 sys_role 行 → 权限解析空集
        Set<String> noPermissionRoles = Set.of(tag + "_unbound_role");
        List<MockHttpServletRequestBuilder> endpoints = List.of(
                get(LIST_URL),
                get(LIST_URL + "/123"),
                post(LIST_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(packageBody(tag + "无权套餐", null, List.of()))),
                put(LIST_URL + "/123").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(packageBody(tag + "无权套餐", null, List.of()))),
                delete(LIST_URL + "/123"));

        for (MockHttpServletRequestBuilder endpoint : endpoints) {
            MvcResult result = performAs("1", "default", noPermissionRoles, endpoint);
            assertThat(result.getResponse().getStatus())
                    .as("无 system:tenant-package:* 权限应 403（PERMISSION_DENIED 登记 403）：%s", endpoint)
                    .isEqualTo(403);
            JsonNode body = bodyOf(result);
            assertThat(body.path("success").asBoolean())
                    .as("403 以 Result 信封表达：%s", endpoint).isFalse();
            assertThat(body.path("code").asText())
                    .as("错误码=既有 PERMISSION_DENIED 三段式码：%s", endpoint)
                    .isEqualTo(SystemErrorCodes.PERMISSION_DENIED);
        }
        // 端点未被无权请求触碰：无套餐数据落行
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package", Long.class)).isZero();
    }

    // ---------- AC-11：V24 种子形状 + 迁移重放幂等 ----------

    @Test
    void should_be_idempotent_when_migration_replayed() throws Exception {
        // ① V24 种子形状（确认⑥钉死）：菜单 46 叶 + 47~49 BUTTON；role_menu 46~49 绑 default 超管；
        //    权限 69~72（list/create/update/delete，GET/POST/PUT/DELETE）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .as("菜单总数=45（V22 水位）+4（46~49，V24）+6（50~55，V29 流程中心，跨 REQ 适配 BPM-3 水位对齐）")
                .isEqualTo(55L);
        var menu46 = jdbcTemplate.queryForMap(
                "select menu_type, parent_id, path, permission_code, status from sys_menu where id = 46");
        assertThat(menu46.get("menu_type")).isEqualTo("MENU");
        assertThat(((Number) menu46.get("parent_id")).longValue()).isEqualTo(1L);
        assertThat(menu46.get("path")).isEqualTo("/tenant-packages");
        assertThat(menu46.get("permission_code")).isEqualTo("system:tenant-package:list");
        assertThat(menu46.get("status")).isEqualTo("ENABLED");
        assertThat(jdbcTemplate.queryForList(
                "select id from sys_menu where menu_type = 'BUTTON' and parent_id = 46 order by id",
                Long.class)).as("三个 BUTTON 挂套餐管理叶下（47/48/49）")
                .containsExactly(47L, 48L, 49L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu rm join sys_menu m on m.id = rm.menu_id "
                        + "where rm.role_id = 1 and rm.tenant_id = 'default' and m.id between 46 and 49",
                Long.class)).as("default 超管补绑 46~49 四行").isEqualTo(4L);
        // 权限点水位为绝对计数、随新增权限点的 REQ 抬升：68 基线 + V24 套餐域 4 点（69~72）
        // + V26 调度域 5 点（73~77，REQ-20261121-071002）
        // + V27 工作流域 5 点（78~82，REQ-20261121-142316）
        // + V32 开通模板/运营视图域 5 点（83~87，REQ-20261122-233426）= 87（SystemIntegrationTest 同源口径）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .as("权限点总数水位=68 基线+V24 套餐域 4 点+V26 调度域 5 点+V27 工作流域 5 点"
                        + "+V32 开通模板/运营视图域 5 点").isEqualTo(87L);
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where id between 69 and 72 order by id",
                String.class)).containsExactly(
                "system:tenant-package:list", "system:tenant-package:create",
                "system:tenant-package:update", "system:tenant-package:delete");
        assertThat(jdbcTemplate.queryForList(
                "select http_method from sys_permission where id between 69 and 72 order by id",
                String.class)).containsExactly("GET", "POST", "PUT", "DELETE");
        assertThat(jdbcTemplate.queryForList(
                "select http_path from sys_permission where id between 69 and 72 order by id",
                String.class)).allSatisfy(path -> assertThat(path).startsWith("/admin/v1/system/tenant-packages"));

        // ② 种子 INSERT 重放（幂等口径同 SystemIntegrationTest：固定主键 + ON CONFLICT DO NOTHING
        //    / 旧值守卫 UPDATE → 空操作；DDL 由 Flyway 一次性执行不在重放范围）。重放清单恒为 V24
        //    （类域=V24 幂等）：重放断言全部为 before/after 相对口径，V26 已由 Flyway 应用并计入
        //    before 快照，无需纳入本类重放清单——跨版本全量重放由
        //    SystemIntegrationTest.seedScriptReplayIsIdempotent 承载（V26 在其清单内）。
        long menusBefore = jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class);
        long roleMenuV24Before = jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu where menu_id between 46 and 49", Long.class);
        long permissionsBefore = jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class);
        long packagesBefore = jdbcTemplate.queryForObject("select count(*) from sys_tenant_package", Long.class);

        replayV24SeedInserts();

        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .isEqualTo(menusBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu where menu_id between 46 and 49", Long.class))
                .isEqualTo(roleMenuV24Before);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .isEqualTo(permissionsBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_tenant_package", Long.class))
                .isEqualTo(packagesBefore);
    }

    /** 定位并重放 V24 迁移的 INSERT 语句（文件名后缀未钉死，按 V24__ 前缀解析；仅重放 INSERT）。 */
    private void replayV24SeedInserts() throws IOException {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:db/migration/system/V24__*.sql");
        assertThat(resources).as("V24 迁移脚本缺失（backend 未落盘或命名不符 V24__*.sql）").isNotEmpty();
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
}
