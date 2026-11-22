package com.acme.platform.system;

import com.acme.common.context.Channel;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261121-054613 服务能力开关集成测试（Testcontainers PG，装配口径照
 * {@code REQ20261121022910TenantPackageApiIntegrationTest}/{@code REQ20261117011514InternalSystemConfigApiTest}；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-1~7（§确认记录②③④、§API；AC-8 既有套件回归=SystemIntegrationTest /
 * SystemErrorI18nTest 定向复验，见交付报告，不在本类）：</p>
 * <ul>
 *   <li>AC-1：V25 种子形状（sys_capability 目录 2 行：1=platform:workflow 工作流 /
 *       2=platform:scheduler 调度中心，均 ENABLED；绑定表就绪零行）+ 种子重放幂等
 *       （SystemIntegrationTest.seedScriptReplayIsIdempotent 同款 INSERT 重放口径）+
 *       基线计数不变（菜单 49/权限 82/BUTTON 30——确认③：V25 无新菜单/权限点/role_permission；
 *       权限点水位为绝对计数、V27 工作流域起为 82，REQ-20261121-142316）+
 *       能力目录端点形状（仅 ENABLED、id asc）。</li>
 *   <li>AC-2：套餐创建携带合法能力码 → 绑定行落库 + 详情 capabilities 回传一致；缺省=空集。</li>
 *   <li>AC-3：更新全量替换（增/删双向差异同步绑定表）；缺省=空集（与 menuIds 同口径）。</li>
 *   <li>AC-4：未知能力码（创建/更新）→ TENANT_CAPABILITY_INVALID（wire 码 14-0006，TL 钉死），
 *       拒绝后数据不变。</li>
 *   <li>AC-5：租户绑定套餐 → internal GET /internal/v1/system/capabilities/current 返回套餐能力码；
 *       套餐增删能力 → 再查即时生效（读时实时）。</li>
 *   <li>AC-6：fail-closed 双态——未绑定套餐租户 → 全部启用能力码（TL 钉死「未绑定→全部启用码」）；
 *       缺 TenantContext（内部令牌无 tenantId claim）→ 空数组。确认④「缺租户→全部启用」与实现
 *       对 sys_tenant 无行的空数组口径存在歧义（TL 钉死事实未覆盖），随交付报告上报裁决。</li>
 *   <li>AC-7：无 system:tenant-package:list 权限访问目录端点 → 403（既有 RBAC 翻译器口径，
 *       PERMISSION_DENIED 登记 403，无权装配照 MessageApi/套餐五端点先例）。</li>
 * </ul>
 *
 * <p>业务失败一律 Result 信封 code≠0，HTTP 状态对齐框架翻译器现状不断言具体 4xx（既有口径）；
 * wire 码断言引用 SystemErrorCodes 常量（码名契约钉死），字面量段位仅对 TL 钉死的 14-0006 加一道
 * 常量-字面量一致性锚。internal 端点无权限注解，MockMvc 直调（InternalTokenAuthFilter 仅验内部令牌）。</p>
 *
 * <p>夹具口径：每用例以 SEQ 唯一 tag 建独立租户夹具行（id 自 922054613000 起）；@AfterEach 物理
 * 清理套餐/能力绑定表（本容器无种子行，用例是唯一写入方）与夹具租户；sys_capability 仅清夹具
 * 自插行（V25 种子 2 行保留，同 JVM 内后续用例共享）。断言值全部由契约语义 + V25 种子锚点推导。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1「缺 TenantContext」以内部令牌不含 tenantId claim 模拟（InternalTokenAuthFilter 对验签请求
 *     恒绑定 TenantContext——无 claim 即上下文租户为空，是 MockMvc 面唯一可达的缺上下文形态）；
 * A-2 internal current 的租户解析键=TenantContext.tenantId()（=sys_tenant.tenant_code，请求惯例口径）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-capability-it-secret-012345678"
})
class REQ20261121054613CapabilityApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req054613-it";

    /** 夹具行 id 基点（种子权限/菜单/能力 id ≤82——V27 工作流域权限续编 78~82；雪花 id 远大于此，teardown 按夹具租户码清理）。 */
    private static final long ID_BASE = 922_054_613_000L;

    // ---- V25 种子锚点（确认③钉死）：能力目录 2 行，均 ENABLED ----
    private static final long SEED_CAP_WORKFLOW_ID = 1L;
    private static final long SEED_CAP_SCHEDULER_ID = 2L;
    private static final String WORKFLOW = "platform:workflow";
    private static final String SCHEDULER = "platform:scheduler";

    private static final String UNKNOWN_CAPABILITY = "platform:ghost-it-054613";

    private static final String CATALOG_URL = "/admin/v1/system/capabilities";
    private static final String PACKAGE_URL = "/admin/v1/system/tenant-packages";
    private static final String CURRENT_URL = "/internal/v1/system/capabilities/current";

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

    /** 每用例唯一 tag（ASCII 小写）：锚定夹具租户编码，互不串扰。 */
    private String tag;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        tag = "req054613t" + SEQ.incrementAndGet();
    }

    @AfterEach
    void cleanUp() {
        // 夹具脏数据 teardown：套餐/能力绑定表整表清理（本容器无种子行）；能力目录仅清夹具自插行
        // （id>=ID_BASE，V25 种子 1/2 保留）；租户按夹具码清理
        jdbcTemplate.update("delete from sys_tenant_package_capability");
        jdbcTemplate.update("delete from sys_tenant_package");
        jdbcTemplate.update("delete from sys_capability where id >= ?", ID_BASE);
        jdbcTemplate.update("delete from sys_tenant where tenant_code = ?", fixtureTenantCode());
        TenantContext.clear();
        UserContext.clear();
    }

    private String fixtureTenantCode() {
        return tag + "-tenant";
    }

    // ---------- 夹具装配（JdbcTemplate 直插，TenantPackageApi 集成测试先例口径） ----------

    /** 建夹具租户行（能力生效面仅需租户主行；套餐绑定经 admin 端点完成）。 */
    private long seedTenantRow() {
        long tenantId = ID_BASE + SEQ.incrementAndGet() * 10;
        jdbcTemplate.update("""
                insert into sys_tenant (id, tenant_code, tenant_name, status,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'ENABLED', now(), ?, now(), ?, 0, false)
                """, tenantId, fixtureTenantCode(), tag + "夹具租户", OPERATOR, OPERATOR);
        return tenantId;
    }

    /** 直插一条非启用能力目录行（AC-1 目录仅 ENABLED 过滤断言的数据面；teardown 按基点清）。 */
    private void seedDisabledCapability(long id, String code) {
        jdbcTemplate.update("""
                insert into sys_capability (id, capability_code, capability_name, status,
                                            created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'DISABLED', now(), ?, now(), ?, 0, false)
                """, id, code, tag + "停用能力", OPERATOR, OPERATOR);
    }

    /** 建套餐（绕过 admin 面，供 AC-6 直插绑定行态）。 */
    private long seedPackageRow(String name) {
        long packageId = ID_BASE + SEQ.incrementAndGet() * 10 + 5;
        jdbcTemplate.update("""
                insert into sys_tenant_package (id, package_name, remark,
                                                created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, null, now(), ?, now(), ?, 0, false)
                """, packageId, name, OPERATOR, OPERATOR);
        return packageId;
    }

    // ---------- HTTP 支撑 ----------

    private String token(String userId, String tenant, Set<String> roles) {
        return tokenIssuer.issue(new AuthenticatedSubject(userId, tenant, Channel.B, roles));
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

    /**
     * 套餐请求体。capabilities 语义三态：非 null=随体数组；null=缺省（字段不出现，
     * 契约「创建/更新缺省=空集」腿的载荷形态）。
     */
    private static ObjectNode packageBody(String name, String remark, List<Long> menuIds,
                                          List<String> capabilities) {
        ObjectNode node = JSON.createObjectNode();
        node.put("packageName", name);
        if (remark != null) {
            node.put("remark", remark);
        }
        var menus = node.putArray("menuIds");
        menuIds.forEach(menus::add);
        if (capabilities != null) {
            var caps = node.putArray("capabilities");
            capabilities.forEach(caps::add);
        }
        return node;
    }

    /** 创建套餐并断言成功信封，返回套餐 id（数值/字符串 wire 双兼容经 asLong 归一）。 */
    private long createPackageOk(String name, String remark, List<Long> menuIds,
                                 List<String> capabilities) throws Exception {
        JsonNode body = callAsSuperAdmin(post(PACKAGE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(packageBody(name, remark, menuIds, capabilities))));
        assertThat(body.path("success").asBoolean()).as("创建套餐应成功：%s", body).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");
        return body.path("data").path("id").asLong();
    }

    private JsonNode packageDetail(long packageId) throws Exception {
        return callAsSuperAdmin(get(PACKAGE_URL + "/" + packageId));
    }

    /** 更新套餐（乐观锁：先取详情 version 随体提交；capabilities=null=字段缺省）。 */
    private JsonNode updatePackage(long packageId, String name, String remark, List<Long> menuIds,
                                   List<String> capabilities) throws Exception {
        JsonNode current = packageDetail(packageId);
        assertThat(current.path("success").asBoolean()).as("套餐详情应存在：%s", current).isTrue();
        ObjectNode body = packageBody(name, remark, menuIds, capabilities);
        body.put("version", current.path("data").path("version").asLong());
        return callAsSuperAdmin(put(PACKAGE_URL + "/" + packageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

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

    // ---------- 读取支撑 ----------

    private List<String> detailCapabilities(JsonNode detail) {
        List<String> codes = new ArrayList<>();
        detail.path("data").path("capabilities").forEach(n -> codes.add(n.asText()));
        return codes;
    }

    private List<String> boundCapabilityCodes(long packageId) {
        return jdbcTemplate.queryForList(
                "select c.capability_code from sys_tenant_package_capability pc "
                        + "join sys_capability c on c.id = pc.capability_id "
                        + "where pc.package_id = ? order by c.id",
                String.class, packageId);
    }

    private long boundCapabilityRowCount(long packageId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package_capability where package_id = ?",
                Long.class, packageId);
    }

    /** internal current（MockMvc 直调，internal 无权限注解）：tenantCode=null=内部令牌无租户 claim。 */
    private List<String> internalCurrentCodes(String tenantCode) throws Exception {
        MvcResult result = mvc.perform(get(CURRENT_URL)
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                "platform-workflow-it", tenantCode, Channel.B, Set.of())))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("internal current 应可访问").isEqualTo(200);
        JsonNode body = bodyOf(result);
        assertThat(body.path("success").asBoolean()).as("internal current 信封应成功：%s", body).isTrue();
        List<String> codes = new ArrayList<>();
        body.path("data").forEach(n -> codes.add(n.asText()));
        return codes;
    }

    private JsonNode capabilityCatalog() throws Exception {
        return callAsSuperAdmin(get(CATALOG_URL));
    }

    private List<Long> catalogIds(JsonNode catalog) {
        List<Long> ids = new ArrayList<>();
        catalog.path("data").forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    private List<String> catalogCodes(JsonNode catalog) {
        List<String> codes = new ArrayList<>();
        catalog.path("data").forEach(n -> codes.add(n.path("capabilityCode").asText()));
        return codes;
    }

    // ---------- AC-1：V25 种子形状 + 迁移重放幂等 + 基线计数不变 + 目录端点形状 ----------

    @Test
    void should_seed_capability_catalog_idempotently_when_v25_replayed() throws Exception {
        // ① V25 种子形状（确认③钉死）：sys_capability 目录 2 行（1=工作流/2=调度中心，均 ENABLED）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_capability", Long.class))
                .as("V25 后能力目录总数=2").isEqualTo(2L);
        var workflow = jdbcTemplate.queryForMap(
                "select capability_code, capability_name, status from sys_capability where id = ?",
                SEED_CAP_WORKFLOW_ID);
        assertThat(workflow.get("capability_code")).isEqualTo(WORKFLOW);
        assertThat(workflow.get("capability_name")).isEqualTo("工作流");
        assertThat(workflow.get("status")).isEqualTo("ENABLED");
        var scheduler = jdbcTemplate.queryForMap(
                "select capability_code, capability_name, status from sys_capability where id = ?",
                SEED_CAP_SCHEDULER_ID);
        assertThat(scheduler.get("capability_code")).isEqualTo(SCHEDULER);
        assertThat(scheduler.get("capability_name")).isEqualTo("调度中心");
        assertThat(scheduler.get("status")).isEqualTo("ENABLED");
        // 绑定表就绪且零初始绑定（确认③：无套餐初始绑定，opt-in）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package_capability", Long.class)).isZero();

        // ② 基线计数不变（确认③：V25 无新菜单/权限点/role_permission——菜单/BUTTON 水位仍 49/30；
        //    权限点水位为绝对计数、随新增权限点的 REQ 抬升：68 基线 + V24 套餐域 4 点
        //    + V26 调度域 5 点（REQ-20261121-071002）+ V27 工作流域 5 点（REQ-20261121-142316）
        //    + V32 开通模板/运营视图域 5 点（REQ-20261122-233426）
        //    = 87，SystemIntegrationTest 同源口径）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .as("V25 不新增权限点（V32 水位 87）").isEqualTo(87L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .as("V25 不新增菜单（V24 水位 49＋V29 流程中心 6=55，跨 REQ 适配 BPM-3 水位对齐）").isEqualTo(55L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class))
                .as("V25 不新增 BUTTON（V24 水位 30＋V29 BUTTON×3=33，跨 REQ 适配 BPM-3 水位对齐）").isEqualTo(33L);

        // ③ 目录端点形状（§API）：全量启用列表、id asc、仅 ENABLED
        JsonNode catalog = capabilityCatalog();
        assertThat(catalog.path("success").asBoolean()).isTrue();
        assertThat(catalogCodes(catalog)).containsExactly(WORKFLOW, SCHEDULER);
        assertThat(catalogIds(catalog)).containsExactly(SEED_CAP_WORKFLOW_ID, SEED_CAP_SCHEDULER_ID);
        catalog.path("data").forEach(n -> assertThat(n.path("status").asText()).isEqualTo("ENABLED"));
        // 非启用行不入目录（§API 仅 ENABLED）：直插 DISABLED 行后目录仍 2 行
        seedDisabledCapability(ID_BASE + 1, "platform:disabled-it");
        assertThat(catalogCodes(capabilityCatalog()))
                .as("DISABLED 行不得出现在启用目录").containsExactly(WORKFLOW, SCHEDULER);

        // ④ V25 种子 INSERT 重放（幂等口径同 SystemIntegrationTest.seedScriptReplayIsIdempotent：
        //    固定主键 + ON CONFLICT DO NOTHING → 空操作；DDL 由 Flyway 一次性执行不在重放范围）
        long capabilitiesBefore = jdbcTemplate.queryForObject("select count(*) from sys_capability", Long.class);
        long bindingsBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package_capability", Long.class);
        long menusBefore = jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class);
        long permissionsBefore = jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class);

        replayV25SeedInserts();

        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_capability", Long.class))
                .as("目录重放不翻倍").isEqualTo(capabilitiesBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package_capability", Long.class))
                .as("绑定表重放不翻倍").isEqualTo(bindingsBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .isEqualTo(menusBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .isEqualTo(permissionsBefore);
    }

    /** 定位并重放 V25 迁移的 INSERT 语句（文件名后缀未钉死，按 V25__ 前缀解析；仅重放 INSERT）。 */
    private void replayV25SeedInserts() throws IOException {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:db/migration/system/V25__*.sql");
        assertThat(resources).as("V25 迁移脚本缺失（backend 未落盘或命名不符 V25__*.sql）").isNotEmpty();
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

    // ---------- AC-2：创建携带合法能力码 → 落库 + 详情回传一致；缺省=空集 ----------

    @Test
    void should_persist_and_return_capabilities_when_package_saved() throws Exception {
        long packageId = createPackageOk(tag + "能力套餐", "集成样例备注",
                List.of(), List.of(WORKFLOW, SCHEDULER));

        // 详情回传：capabilities 与提交一致（数值/字符串 wire 双兼容经文本归一）
        JsonNode detail = packageDetail(packageId);
        assertThat(detail.path("success").asBoolean()).isTrue();
        assertThat(detailCapabilities(detail))
                .as("详情应回传提交的能力码全集").containsExactlyInAnyOrder(WORKFLOW, SCHEDULER);

        // 绑定行落库：uk(package_id, capability_id) 两行，码面=目录种子码
        assertThat(boundCapabilityRowCount(packageId)).isEqualTo(2L);
        assertThat(boundCapabilityCodes(packageId))
                .containsExactlyInAnyOrder(WORKFLOW, SCHEDULER);

        // 创建缺省=空集（契约：null/缺省语义不存在——不传字段即空集全量替换）
        long plainId = createPackageOk(tag + "缺省套餐", null, List.of(), null);
        assertThat(detailCapabilities(packageDetail(plainId)))
                .as("缺省创建的能力集应为空").isEmpty();
        assertThat(boundCapabilityRowCount(plainId)).isZero();
    }

    // ---------- AC-3：更新全量替换（增/删双向差异同步）；缺省=空集 ----------

    @Test
    void should_replace_capability_bindings_when_package_updated() throws Exception {
        long packageId = createPackageOk(tag + "替换套餐", null, List.of(), List.of(WORKFLOW));
        assertThat(boundCapabilityCodes(packageId)).containsExactly(WORKFLOW);

        // 增方向：[workflow] → [workflow, scheduler]，绑定行差异同步为两行
        JsonNode grow = updatePackage(packageId, tag + "替换套餐", null, List.of(),
                List.of(WORKFLOW, SCHEDULER));
        assertThat(grow.path("success").asBoolean()).as("更新（增）应成功：%s", grow).isTrue();
        assertThat(boundCapabilityRowCount(packageId)).isEqualTo(2L);
        assertThat(detailCapabilities(packageDetail(packageId)))
                .containsExactlyInAnyOrder(WORKFLOW, SCHEDULER);

        // 删方向：[workflow, scheduler] → [scheduler]，旧行删除新行保留（无孤儿行）
        JsonNode shrink = updatePackage(packageId, tag + "替换套餐", null, List.of(),
                List.of(SCHEDULER));
        assertThat(shrink.path("success").asBoolean()).as("更新（删）应成功：%s", shrink).isTrue();
        assertThat(boundCapabilityRowCount(packageId)).as("全量替换后绑定行数=1").isEqualTo(1L);
        assertThat(boundCapabilityCodes(packageId))
                .as("被移除能力码不得残留绑定行").containsExactly(SCHEDULER);
        assertThat(detailCapabilities(packageDetail(packageId))).containsExactly(SCHEDULER);

        // 更新缺省=空集（与 menuIds 同口径全量替换）：不传 capabilities 字段 → 绑定清空
        JsonNode absent = updatePackage(packageId, tag + "替换套餐", null, List.of(), null);
        assertThat(absent.path("success").asBoolean()).as("缺省更新应成功：%s", absent).isTrue();
        assertThat(boundCapabilityRowCount(packageId)).isZero();
        assertThat(detailCapabilities(packageDetail(packageId))).isEmpty();
    }

    // ---------- AC-4：未知能力码（创建/更新）→ TENANT_CAPABILITY_INVALID，数据不变 ----------

    @Test
    void should_reject_unknown_capability_codes_when_saved() throws Exception {
        // wire 码契约钉死 14-0006 段位（TL 钉死事实；三段式前缀=域码归实现，段位字面量不猜测）
        assertThat(SystemErrorCodes.TENANT_CAPABILITY_INVALID)
                .as("TENANT_CAPABILITY_INVALID wire 码应含契约钉死段位 14-0006").endsWith("14-0006");

        long totalBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package where deleted = false", Long.class);

        // 创建：未知码 → 业务失败信封（HTTP 对齐翻译器现状不断言具体 4xx）
        JsonNode badCreate = callAsSuperAdmin(post(PACKAGE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(packageBody(tag + "未知能力套餐", null,
                        List.of(), List.of(WORKFLOW, UNKNOWN_CAPABILITY)))));
        assertThat(badCreate.path("success").asBoolean()).isFalse();
        assertThat(badCreate.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_CAPABILITY_INVALID);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_package where deleted = false", Long.class))
                .as("失败创建不落行").isEqualTo(totalBefore);

        // 更新：未知码 → 同码拒绝，且原绑定不变（全量替换仅在成功时发生）
        long packageId = createPackageOk(tag + "合法能力套餐", null, List.of(), List.of(WORKFLOW));
        JsonNode badUpdate = updatePackage(packageId, tag + "合法能力套餐", null, List.of(),
                List.of(SCHEDULER, UNKNOWN_CAPABILITY));
        assertThat(badUpdate.path("success").asBoolean()).isFalse();
        assertThat(badUpdate.path("code").asText())
                .isEqualTo(SystemErrorCodes.TENANT_CAPABILITY_INVALID);
        assertThat(boundCapabilityCodes(packageId))
                .as("拒绝更新后原绑定不变").containsExactly(WORKFLOW);
        assertThat(detailCapabilities(packageDetail(packageId))).containsExactly(WORKFLOW);
    }

    // ---------- AC-5：绑定租户 internal current=套餐能力码；套餐增删 → 即时生效 ----------

    @Test
    void should_resolve_effective_capabilities_realtime_for_bound_tenant() throws Exception {
        long tenantId = seedTenantRow();
        long packageId = createPackageOk(tag + "生效套餐", null, List.of(), List.of(WORKFLOW));
        JsonNode bind = putTenantPackage(tenantId, packageId);
        assertThat(bind.path("success").asBoolean()).as("绑定套餐应成功：%s", bind).isTrue();

        // 绑定 → current=套餐能力码（TenantContext 驱动，内部令牌租户 claim=夹具租户码）
        assertThat(internalCurrentCodes(fixtureTenantCode()))
                .as("绑定租户生效能力=套餐能力集").containsExactly(WORKFLOW);

        // 套餐增删能力（不重绑租户）→ 再查即时生效（读时实时）
        JsonNode replace = updatePackage(packageId, tag + "生效套餐", null, List.of(),
                List.of(SCHEDULER));
        assertThat(replace.path("success").asBoolean()).as("套餐能力替换应成功：%s", replace).isTrue();
        assertThat(internalCurrentCodes(fixtureTenantCode()))
                .as("套餐改配后旧能力应即时消失、新能力即时出现").containsExactly(SCHEDULER);

        JsonNode grow = updatePackage(packageId, tag + "生效套餐", null, List.of(),
                List.of(WORKFLOW, SCHEDULER));
        assertThat(grow.path("success").asBoolean()).isTrue();
        assertThat(internalCurrentCodes(fixtureTenantCode()))
                .as("套餐追加能力即时并入生效集").containsExactlyInAnyOrder(WORKFLOW, SCHEDULER);
    }

    // ---------- AC-6：fail-closed 双态——未绑定/缺租户=全部启用码；缺 TenantContext=空数组 ----------

    @Test
    void should_return_all_enabled_or_empty_by_tenant_context() throws Exception {
        // ① 存在租户但未绑定套餐 → 全部启用能力码（TL 钉死「未绑定→全部启用码」）
        seedTenantRow();
        assertThat(internalCurrentCodes(fixtureTenantCode()))
                .as("未绑定套餐租户生效能力=全部启用码")
                .containsExactlyInAnyOrder(WORKFLOW, SCHEDULER);

        // ② 缺 TenantContext（内部令牌无 tenantId claim，假定 A-1）→ 空数组 fail-closed
        //（契约歧义留档：确认④「缺租户→全部启用」vs 实现对 sys_tenant 无行上下文返回空数组，
        //  TL 钉死事实未覆盖该态——ghost 租户腿已移除，歧义随交付报告上报 TL 裁决）
        assertThat(internalCurrentCodes(null))
                .as("缺租户上下文应 fail-closed 空数组").isEmpty();
    }

    // ---------- AC-7：无 system:tenant-package:list 权限访问目录 → 403（既有 RBAC 口径） ----------

    @Test
    void should_guard_capability_catalog_by_package_list_permission() throws Exception {
        // 无权限装配（MessageApi 集成测试先例）：令牌角色无 sys_role 行 → 权限解析空集
        Set<String> noPermissionRoles = Set.of(tag + "_unbound_role");
        MvcResult result = performAs("1", "default", noPermissionRoles, get(CATALOG_URL));
        assertThat(result.getResponse().getStatus())
                .as("无 system:tenant-package:list 权限应 403（PERMISSION_DENIED 登记 403）")
                .isEqualTo(403);
        JsonNode body = bodyOf(result);
        assertThat(body.path("success").asBoolean()).as("403 以 Result 信封表达").isFalse();
        assertThat(body.path("code").asText())
                .as("错误码=既有 PERMISSION_DENIED 三段式码")
                .isEqualTo(SystemErrorCodes.PERMISSION_DENIED);
    }
}
