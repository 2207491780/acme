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
import org.springframework.http.HttpStatus;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-150802 租户生命周期宽限期+统一配额框架（TENANT-4）platform-system 服务域集成测试
 * （Testcontainers PG，装配口径照 {@code REQ20261122102954TenantHierarchyIntegrationTest}/
 * {@code SystemIntegrationTest}；无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0
 * 才算验证）。
 *
 * <p>覆盖契约 AC-1~5/8/9（§确认记录②~⑨、§验收标准）：</p>
 * <ul>
 *   <li>AC-1：V31 三变更就绪（sys_tenant.grace_until timestamptz NULL /
 *       sys_tenant_package.quota_storage_bytes bigint NULL / sys_tenant_quota 新表含 tenant_id 唯一
 *       + quota_storage_bytes）+ 存量缺省不动 + 脚本语句重放幂等。</li>
 *   <li>AC-2：配额三级解析链（租户覆盖 sys_tenant_quota > 套餐 quota_storage_bytes > 平台缺省
 *       1GiB）+ PUT quota 清除覆盖回落套餐/缺省（AdminTenantView.quotaStorageBytes=解析后有效值）。</li>
 *   <li>AC-3：解析值=checkQuota 超限判定上界（无覆盖无套餐=平台缺省=旧行为兼容；覆盖切换上界；
 *       0=不限语义保留）。</li>
 *   <li>AC-4：graceUntil 派生数据面——update 收 graceUntil 回读；宽限内租户在登录下拉数据源
 *       /internal/v1/tenants 保留（可登录派生）、宽限也过排除；by-code 内部契约下发
 *       expiresAt+graceUntil（登录闸门派生输入）。</li>
 *   <li>AC-5：GET /tenants/{id}/usage 跨租户旁路聚合（storageBytes=sys_file 未删 size 总和 +
 *       userCount），调用方上下文不限定目标租户（@IgnoreTenant 口径）。</li>
 *   <li>AC-8：租户域既有面零回归——default 不可停用、create 缺省顶层（grace_until 缺省 NULL）、
 *       list 层级字段（TENANT-3 面）、套餐创建绑定（TENANT-1 面）。</li>
 *   <li>AC-9：TENANT-3 级联语义不变（正交验证）——停用父级联停用子孙+逐租户撤会话交互，
 *       恢复父不自动恢复子、恢复不触发撤销。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 wire 字段名：graceUntil / quotaStorageBytes（对齐 AdminTenantView 驼峰命名，
 *       ISO-8601 字符串）；PUT /tenants/{id}/quota body 字段 quotaStorageBytes，null=清除继承
 *       （确认⑦⑧「覆盖+清除继承」）。实现侧 DTO 未落盘时 update 中的 graceUntil 被 Jackson 忽略
 *       → 断言红灯 = 实现缺口非用例缺陷（REQ-022954 先例口径）。</li>
 *   <li>AMB-2 平台缺省=1GiB=1073741824（契约背景钉死 StorageProperties 缺省 1GiB，0=不限；
 *       测试域 yml 无 acme.storage 覆盖）。</li>
 *   <li>AMB-3 AC-3 的 STORAGE_QUOTA_EXCEEDED 上传触达闭环归 platform-infra（FileUseCase.checkQuota
 *       收编面，REQ20261114071607/REQ20261117045754 既有 IT 面）；system-service 不依赖
 *       platform-infra，本类以「解析值=超限上界」承载，infra 闭环收编与否交 TL 裁决。</li>
 *   <li>AMB-4 AC-4 登录放行/拒绝最终闸门在 platform-auth（LoginUseCase.verifyTenantLoginable 消费
 *       by-code 契约）；契约变更清单未列 platform-auth——本类以 platform-system 侧派生数据面承载
 *       （list 回读 / 下拉谓词宽限内保留 / by-code 下发 graceUntil）。若实现以其他字段名（如派生
 *       布尔 loginable）承载，红灯交 TL 裁决。</li>
 *   <li>AMB-5 AC-5 sys_file 归 platform-infra 迁移（V2__file_job_schema），system IT 库无此表——
 *       本类以 infra V2 同列集自举（CREATE TABLE IF NOT EXISTS，replay-safe）；聚合列集漂移则
 *       红灯交 TL。</li>
 *   <li>AMB-6 /internal/v1 端点以超管访问令牌直调（InternalTokenAuthFilter 对 internal 路径豁免
 *       端别校验；信封 ContractResult 与 Result 同形宽容）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-tenant-lifecycle-it-secret-01234"
})
class REQ20261122150802TenantLifecycleQuotaIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req150802-it";

    private static final String LIST_URL = "/admin/v1/system/tenants";

    private static final String INTERNAL_LIST_URL = "/internal/v1/tenants";

    /** 平台缺省配额（契约背景钉死：StorageProperties.defaultBytes 缺省 1GiB，AMB-2）。 */
    private static final long PLATFORM_DEFAULT_QUOTA = 1073741824L;

    /** 夹具租户码前缀（teardown 按前缀物理清理；唯一性靠每用例独立前缀）。 */
    private static final String FIXTURE_PREFIX = "req150802";

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

    /** 撤会话网关桩（AC-9 正交验证沿用 TENANT-3 交互口径）。 */
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
        // 夹具脏数据 teardown：按夹具前缀物理清理（先配额/文件旁表再主表；init 建出的角色/用户/绑定随租户码清除）
        // sys_file 归 platform-infra 数据域：仅用量用例 bootstrap 建表后才存在，清理先判表存在
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select to_regclass('sys_file') is not null", Boolean.class))) {
            jdbcTemplate.update("delete from sys_file where tenant_id like ?", FIXTURE_PREFIX + "%");
        }
        jdbcTemplate.update(
                "delete from sys_tenant_quota where tenant_id in "
                        + "(select id from sys_tenant where tenant_code like ?)", FIXTURE_PREFIX + "%");
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

    // ---------- AC-1：V31 三变更就绪 + 存量缺省不动 + 重放幂等 ----------

    @Test
    void should_apply_v31_grace_and_quota_columns_and_quota_table_and_be_idempotent_when_migration_executed_and_replayed()
            throws Exception {
        // ① 迁移脚本存在（backend 并行交付锚点）
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:db/migration/system/V31__*.sql");
        assertThat(resources).as("V31 迁移脚本缺失（backend 未落盘或命名不符 V31__*.sql）").isNotEmpty();

        // ② sys_tenant.grace_until：timestamptz 可空（契约确认②：宽限期止点，NULL=未配置）
        Map<String, Object> graceColumn = jdbcTemplate.queryForMap(
                "select udt_name, is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant' and column_name='grace_until'");
        assertThat(String.valueOf(graceColumn.get("udt_name"))).as("grace_until 应为 timestamptz")
                .isEqualTo("timestamptz");
        assertThat(graceColumn.get("is_nullable")).as("grace_until 可空（NULL=未配置宽限）").isEqualTo("YES");

        // ③ sys_tenant_package.quota_storage_bytes：bigint 可空（确认③：NULL=继承平台缺省）
        Map<String, Object> packageQuotaColumn = jdbcTemplate.queryForMap(
                "select udt_name, is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant_package' "
                        + "and column_name='quota_storage_bytes'");
        assertThat(String.valueOf(packageQuotaColumn.get("udt_name"))).as("套餐配额列应为 bigint")
                .isEqualTo("int8");
        assertThat(packageQuotaColumn.get("is_nullable")).as("套餐配额列可空（NULL=平台缺省）").isEqualTo("YES");

        // ④ sys_tenant_quota 新表：tenant_id 唯一 + quota_storage_bytes bigint 可空（NULL=继承套餐）
        Integer quotaTableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema='public' and table_name='sys_tenant_quota'", Integer.class);
        assertThat(quotaTableCount).as("sys_tenant_quota 表就绪").isEqualTo(1);
        Map<String, Object> quotaColumn = jdbcTemplate.queryForMap(
                "select udt_name, is_nullable from information_schema.columns "
                        + "where table_schema='public' and table_name='sys_tenant_quota' "
                        + "and column_name='quota_storage_bytes'");
        assertThat(String.valueOf(quotaColumn.get("udt_name"))).as("租户覆盖配额列应为 bigint").isEqualTo("int8");
        assertThat(quotaColumn.get("is_nullable")).as("租户覆盖配额列可空（NULL=继承套餐）").isEqualTo("YES");
        Integer uniqueTenantIdCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint where conrelid='sys_tenant_quota'::regclass "
                        + "and contype='u'", Integer.class);
        Integer uniqueIndexCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname='public' and tablename='sys_tenant_quota' "
                        + "and indexdef ilike '%unique%'", Integer.class);
        assertThat(Math.max(uniqueTenantIdCount, uniqueIndexCount))
                .as("sys_tenant_quota.tenant_id 应有唯一约束/唯一索引（确认③ tenant_id uk）").isGreaterThanOrEqualTo(1);

        // ⑤ 存量缺省不动：default 种子租户 grace_until=NULL（不因迁移被赋宽限）
        Map<String, Object> defaultTenant = jdbcTemplate.queryForMap(
                "select grace_until from sys_tenant where tenant_code = 'default'");
        assertThat(defaultTenant.get("grace_until")).as("存量租户 grace_until 缺省 NULL").isNull();

        // ⑥ 重放幂等：语句原样重放后三变更数据面零变化
        List<Map<String, Object>> before = lifecycleSnapshot();
        replayV31Statements(resources);
        assertThat(lifecycleSnapshot()).as("重放后生命周期/配额数据零变化（幂等）").isEqualTo(before);
    }

    private List<Map<String, Object>> lifecycleSnapshot() {
        return jdbcTemplate.queryForList(
                "select t.tenant_code, t.grace_until, p.package_name, p.quota_storage_bytes "
                        + "from sys_tenant t left join sys_tenant_package p on p.id = t.package_id "
                        + "order by t.tenant_code");
    }

    private void replayV31Statements(org.springframework.core.io.Resource[] resources) throws IOException {
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

    // ---------- AC-2：配额三级解析链 + 清除回落 ----------

    @Test
    void should_resolve_quota_tenant_override_then_package_then_platform_default_when_chain_evaluated()
            throws Exception {
        // ① 无覆盖无套餐 → 平台缺省 1GiB（链尾）
        String plainCode = createTenantOk(FIXTURE_PREFIX + "-q-plain");
        assertThat(resolvedQuotaOf(plainCode)).as("无覆盖无套餐=平台缺省 1GiB（AMB-2）")
                .isEqualTo(PLATFORM_DEFAULT_QUOTA);

        // ② 套餐默认：套餐 quota_storage_bytes=5000 → 绑定租户解析=5000
        long packageId = createPackageOk(FIXTURE_PREFIX + "配额套餐");
        jdbcTemplate.update("update sys_tenant_package set quota_storage_bytes = 5000 where id = ?", packageId);
        String boundCode = createTenantOk(FIXTURE_PREFIX + "-q-bound");
        bindPackage(boundCode, packageId);
        assertThat(resolvedQuotaOf(boundCode)).as("套餐默认生效（覆盖缺省）").isEqualTo(5000L);

        // ③ 租户覆盖最高优先：PUT quota 9999 → 解析=9999（> 套餐）
        long boundId = tenantIdByCode(boundCode);
        assertThat(putQuota(boundId, 9999L).path("success").asBoolean()).as("PUT quota 覆盖应成功").isTrue();
        assertThat(resolvedQuotaOf(boundCode)).as("租户覆盖 > 套餐默认").isEqualTo(9999L);
        Map<String, Object> overrideRow = jdbcTemplate.queryForMap(
                "select quota_storage_bytes from sys_tenant_quota where tenant_id = ?", boundId);
        assertThat(((Number) overrideRow.get("quota_storage_bytes")).longValue())
                .as("覆盖值落 sys_tenant_quota（确认③租户覆盖表）").isEqualTo(9999L);

        // ④ 清除覆盖回落：PUT quota null → 回落套餐 5000
        assertThat(putQuota(boundId, null).path("success").asBoolean()).as("清除覆盖应成功").isTrue();
        assertThat(resolvedQuotaOf(boundCode)).as("清除覆盖回落套餐默认（确认③清除回落）").isEqualTo(5000L);

        // ⑤ 套餐也缺 → 回落平台缺省
        jdbcTemplate.update("update sys_tenant_package set quota_storage_bytes = null where id = ?", packageId);
        assertThat(resolvedQuotaOf(boundCode)).as("套餐清空回落平台缺省（三级链尾）")
                .isEqualTo(PLATFORM_DEFAULT_QUOTA);
    }

    // ---------- AC-3：超限上界=解析值 + 平台缺省兼容 ----------

    @Test
    void should_keep_platform_default_compatibility_and_switch_enforcement_bound_when_tenant_quota_set()
            throws Exception {
        // ① 旧行为兼容：无覆盖无套餐租户解析=平台缺省（checkQuota 原平台单一全局值口径不变面）
        String legacyCode = createTenantOk(FIXTURE_PREFIX + "-q-legacy");
        assertThat(resolvedQuotaOf(legacyCode)).as("旧平台缺省行为兼容（无覆盖无套餐=平台值，AMB-2）")
                .isEqualTo(PLATFORM_DEFAULT_QUOTA);

        // ② 超限判定上界切换：覆盖 100 → 解析=100（FileUseCase.checkQuota 按解析值拒绝，
        //     STORAGE_QUOTA_EXCEEDED 触达闭环归 platform-infra——AMB-3）
        String tightCode = createTenantOk(FIXTURE_PREFIX + "-q-tight");
        assertThat(putQuota(tenantIdByCode(tightCode), 100L).path("success").asBoolean()).isTrue();
        assertThat(resolvedQuotaOf(tightCode)).as("超限上界切至租户覆盖值").isEqualTo(100L);

        // ③ 0=不限语义保留（StorageProperties 口径沿 checkQuota 保留，确认③）
        String unlimitedCode = createTenantOk(FIXTURE_PREFIX + "-q-unlimited");
        assertThat(putQuota(tenantIdByCode(unlimitedCode), 0L).path("success").asBoolean()).isTrue();
        assertThat(resolvedQuotaOf(unlimitedCode)).as("覆盖 0=不限（语义保留，AMB-1）").isEqualTo(0L);
    }

    // ---------- AC-4：graceUntil 派生数据面（登录放行通道宽限内保留/宽限过排除） ----------

    @Test
    void should_expose_grace_derived_surface_when_tenant_in_grace_and_lock_after_grace_expired()
            throws Exception {
        Instant now = Instant.now();
        // G：宽限期内（expires 过、grace 未过）→ 登录下拉数据源保留（确认②宽限期内=仍可登录）
        String graceCode = createTenantOk(FIXTURE_PREFIX + "-life-grace");
        updateLifecycle(graceCode, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
        // E：宽限也过（expires、grace 均过）→ 派生自动过期，下拉排除
        String expiredCode = createTenantOk(FIXTURE_PREFIX + "-life-expired");
        updateLifecycle(expiredCode, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        // F：未到期无宽限 → 照常保留
        String activeCode = createTenantOk(FIXTURE_PREFIX + "-life-active");
        updateLifecycle(activeCode, now.plus(Duration.ofDays(2)), null);

        // ① 管理面回读：list 记录携 graceUntil（确认⑥ update 收 graceUntil / 确认⑦ view 扩展）
        JsonNode page = callAsSuperAdmin(get(LIST_URL).queryParam("page", "1").queryParam("size", "50"));
        assertThat(page.path("success").asBoolean()).as("list 应成功：%s", page).isTrue();
        JsonNode graceRecord = findRecordByCode(page.path("data").path("records"), graceCode);
        assertThat(graceRecord).as("list 应含宽限期租户").isNotNull();
        assertThat(graceRecord.path("expiresAt").isMissingNode() || graceRecord.path("expiresAt").isNull())
                .as("expiresAt 回读（派生输入）").isFalse();
        assertThat(graceRecord.path("graceUntil").isMissingNode() || graceRecord.path("graceUntil").isNull())
                .as("graceUntil 回读缺失=AdminTenantView 未扩字段（AMB-1 实现缺口锚点）").isFalse();

        // ② 登录下拉数据源：宽限内保留（可登录派生）、宽限也过排除、未到期保留（AMB-4）
        JsonNode loginList = callAsSuperAdmin(get(INTERNAL_LIST_URL));
        assertThat(loginList.path("success").asBoolean()).as("internal 租户清单应成功：%s", loginList).isTrue();
        assertThat(containsTenantCode(loginList.path("data"), graceCode))
                .as("宽限期内租户应保留在登录下拉数据源（确认②可登录）").isTrue();
        assertThat(containsTenantCode(loginList.path("data"), activeCode))
                .as("未到期租户照常保留").isTrue();
        assertThat(containsTenantCode(loginList.path("data"), expiredCode))
                .as("宽限也过租户应被排除（派生自动过期，无定时任务）").isFalse();

        // ③ by-code 内部契约：登录闸门派生输入（expiresAt + graceUntil 下发，AMB-4）
        JsonNode graceByCode = callAsSuperAdmin(get(INTERNAL_LIST_URL + "/by-code")
                .param("tenantCode", graceCode));
        assertThat(graceByCode.path("success").asBoolean()).as("by-code 应成功：%s", graceByCode).isTrue();
        assertThat(graceByCode.path("data").path("tenantCode").asText()).isEqualTo(graceCode);
        assertThat(graceByCode.path("data").path("graceUntil").isMissingNode()
                || graceByCode.path("data").path("graceUntil").isNull())
                .as("by-code 应回发 graceUntil（登录放行派生输入；缺失=TenantInternalView 未扩字段，AMB-4）")
                .isFalse();
    }

    // ---------- AC-5：usage 跨租户旁路聚合 ----------

    @Test
    void should_aggregate_usage_bypass_when_usage_endpoint_queried() throws Exception {
        bootstrapSysFile();

        // 开通租户（含初始管理员）+ 直插 2 名业务用户 → userCount=3
        String usageCode = initTenant(FIXTURE_PREFIX + "-usage");
        seedUserInTenant("req150802-usage-u1", usageCode);
        seedUserInTenant("req150802-usage-u2", usageCode);

        // sys_file：目标租户未删 100+200；已删 500（不计入）；无关租户 999（旁路校验不混入）
        insertSysFile(usageCode, 100L, false);
        insertSysFile(usageCode, 200L, false);
        insertSysFile(usageCode, 500L, true);
        insertSysFile("req150802-other", 999L, false);

        // 调用方处于 default 上下文 → 聚合目标租户数据（@IgnoreTenant 旁路口径，确认④）
        long usageId = tenantIdByCode(usageCode);
        JsonNode usage = callAsSuperAdmin(get(LIST_URL + "/" + usageId + "/usage"));
        assertThat(usage.path("success").asBoolean()).as("usage 端点应成功：%s", usage).isTrue();
        JsonNode data = usage.path("data");
        assertThat(data.path("storageBytes").isMissingNode())
                .as("usage 应含 storageBytes（确认④ wire 缺失=实现缺口）").isFalse();
        assertThat(data.path("storageBytes").asLong()).as("storageBytes=未删 size 总和（100+200）").isEqualTo(300L);
        assertThat(data.path("userCount").asLong()).as("userCount=租户内用户数（管理员+2）").isEqualTo(3L);
    }

    /** system IT 库无 sys_file（platform-infra 迁移域，AMB-5）：以 infra V2 同列集自举（幂等守卫）。 */
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
                Math.abs(System.identityHashCode(tenantCode + size + deleted)) & 0x7FFFFFFFL | 0x500000000L,
                tenantCode + "/" + size + "/" + deleted, "f-" + size, size, tenantCode,
                OPERATOR, OPERATOR, deleted);
    }

    // ---------- AC-9：TENANT-3 级联语义不变（正交验证） ----------

    @Test
    void should_keep_tenant_cascade_semantics_when_lifecycle_extended() throws Exception {
        long[] chain = seedChain(FIXTURE_PREFIX + "-cas"); // P→C→G
        String siblingCode = createTenantOk(FIXTURE_PREFIX + "-cas-sib"); // 无关租户不波及

        when(revocationGateway.revokeByTenant(anyString()))
                .thenReturn(new TenantSessionRevocationGateway.RevocationOutcome(1, 0));

        // 停用父 → 后代全部级联停用 + 逐租户撤会话（TENANT-3 物化语义不变）
        JsonNode disabled = putStatus(chain[0], "DISABLED");
        assertThat(disabled.path("success").asBoolean()).as("停用父租户应成功：%s", disabled).isTrue();
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-p")).isEqualTo("DISABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-c")).as("子级联停用（语义不变）").isEqualTo("DISABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-g")).as("孙级联停用（语义不变）").isEqualTo("DISABLED");
        assertThat(tenantStatus(siblingCode)).as("无关租户不波及").isEqualTo("ENABLED");

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(revocationGateway, times(3)).revokeByTenant(captor.capture());
        assertThat(captor.getAllValues()).as("父/子/孙逐租户撤会话（语义不变）")
                .containsExactlyInAnyOrder(FIXTURE_PREFIX + "-cas-p", FIXTURE_PREFIX + "-cas-c",
                        FIXTURE_PREFIX + "-cas-g");

        // 恢复父 → 子孙保持停用；恢复不触发撤销（既有口径）
        JsonNode restored = putStatus(chain[0], "ENABLED");
        assertThat(restored.path("success").asBoolean()).as("恢复父租户应成功：%s", restored).isTrue();
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-p")).isEqualTo("ENABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-c")).as("恢复父不自动恢复子").isEqualTo("DISABLED");
        assertThat(tenantStatus(FIXTURE_PREFIX + "-cas-g")).as("恢复父不自动恢复孙").isEqualTo("DISABLED");
        verify(revocationGateway, times(3)).revokeByTenant(anyString());
        verifyNoMoreInteractions(revocationGateway);
    }

    // ---------- AC-8：租户域既有面零回归 ----------

    @Test
    void should_keep_existing_tenant_domain_behaviors_when_lifecycle_quota_added() throws Exception {
        // ① default 租户不可停用（既有口径回归）
        long defaultId = tenantIdByCode("default");
        JsonNode denied = putStatus(defaultId, "DISABLED");
        assertThat(denied.path("success").asBoolean()).as("default 不可停用：%s", denied).isFalse();
        assertThat(denied.path("code").asText()).isEqualTo(SystemErrorCodes.TENANT_DISABLE_DENIED);
        assertThat(tenantStatus("default")).isEqualTo("ENABLED");

        // ② 简单 create：顶层落库 + grace_until 缺省 NULL（V31 列不动既有创建面）
        String createdCode = createTenantOk(FIXTURE_PREFIX + "-base");
        Map<String, Object> createdRow = jdbcTemplate.queryForMap(
                "select parent_id, level, grace_until from sys_tenant where tenant_code = ?", createdCode);
        assertThat(createdRow.get("parent_id")).as("缺省 parentId=顶层").isNull();
        assertThat(((Number) createdRow.get("level")).intValue()).isEqualTo(1);
        assertThat(createdRow.get("grace_until")).as("新建租户 grace_until 缺省 NULL").isNull();

        // ③ list 层级字段仍下发（TENANT-3 面零回归）
        JsonNode page = callAsSuperAdmin(get(LIST_URL).queryParam("page", "1").queryParam("size", "50"));
        assertThat(page.path("success").asBoolean()).isTrue();
        JsonNode record = findRecordByCode(page.path("data").path("records"), createdCode);
        assertThat(record).as("list 应含新建租户行").isNotNull();
        assertThat(record.path("level").asLong()).isEqualTo(1);

        // ④ 套餐创建+绑定（TENANT-1 面零回归）
        long packageId = createPackageOk(FIXTURE_PREFIX + "回归套餐");
        bindPackage(createdCode, packageId);
        assertThat(jdbcTemplate.queryForObject(
                "select package_id from sys_tenant where tenant_code = ?", Long.class, createdCode))
                .as("套餐绑定落库（既有 update 面）").isEqualTo(packageId);
    }

    // ---------- 夹具装配 ----------

    /** 建 P→C→G 三层链（全部走 POST create），返回 {parentId, childId, grandchildId}。 */
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

    private static ObjectNode initBody(String tenantCode) {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 开通夹具租户");
        body.putNull("remark");
        body.put("adminUsername", tenantCode.replace('-', '_') + "_admin");
        body.put("adminNickname", tenantCode + " 管理员");
        // REQ-20261125-230807 确认③：templateCode HTTP 面必填（未命中模板=既有兜底行为，语义不变）
        body.put("templateCode", "fixture-unmatched-template");
        body.putNull("parentId");
        return body;
    }

    /** init 开通（成功信封断言），返回租户码。 */
    private String initTenant(String tenantCode) throws Exception {
        JsonNode result = callAsSuperAdmin(post(LIST_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(initBody(tenantCode))));
        assertThat(result.path("success").asBoolean()).as("init 开通应成功：%s", result).isTrue();
        assertThat(result.path("code").asText()).isEqualTo("0");
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

    /** 租户生命周期更新：expiresAt/graceUntil 可空=null 语义；其余字段按既有 update 全量口径提交。 */
    private JsonNode updateLifecycle(String tenantCode, Instant expiresAt, Instant graceUntil) throws Exception {
        long tenantId = tenantIdByCode(tenantCode);
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantName", tenantNameOf(tenantId));
        body.putNull("remark");
        body.putNull("contactName");
        body.putNull("contactPhone");
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        if (graceUntil == null) {
            body.putNull("graceUntil");
        } else {
            body.put("graceUntil", graceUntil.toString());
        }
        body.put("version", versionOf(tenantId));
        body.putNull("packageId");
        body.putNull("parentId");
        return callAsSuperAdmin(put(LIST_URL + "/" + tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** 套餐绑定（既有 update packageId 面）。 */
    private void bindPackage(String tenantCode, long packageId) throws Exception {
        long tenantId = tenantIdByCode(tenantCode);
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantName", tenantNameOf(tenantId));
        body.put("version", versionOf(tenantId));
        body.put("packageId", packageId);
        body.putNull("parentId");
        JsonNode result = callAsSuperAdmin(put(LIST_URL + "/" + tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean()).as("绑定套餐应成功：%s", result).isTrue();
    }

    /** PUT /tenants/{id}/quota（quotaStorageBytes null=清除继承；REQ-20261125-230807 信封化：成功 wire=Result 信封 code="0"）。 */
    private JsonNode putQuota(long tenantId, Long quotaStorageBytes) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        if (quotaStorageBytes == null) {
            body.putNull("quotaStorageBytes");
        } else {
            body.put("quotaStorageBytes", quotaStorageBytes);
        }
        JsonNode envelope = callAsSuperAdmin(put(LIST_URL + "/" + tenantId + "/quota")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(envelope.path("success").asBoolean())
                .as("配额保存应成功（REQ-20261125-230807 信封化）：%s", envelope).isTrue();
        assertThat(envelope.path("code").asText())
                .as("配额保存成功信封应 code=\"0\"（REQ-20261125-230807 信封化）").isEqualTo("0");
        return envelope;
    }

    /** 解析后有效配额（AdminTenantView.quotaStorageBytes，确认⑦「解析后有效值」）。 */
    private long resolvedQuotaOf(String tenantCode) throws Exception {
        JsonNode page = callAsSuperAdmin(get(LIST_URL).queryParam("page", "1").queryParam("size", "50"));
        assertThat(page.path("success").asBoolean()).as("list 应成功：%s", page).isTrue();
        JsonNode record = findRecordByCode(page.path("data").path("records"), tenantCode);
        assertThat(record).as("list 应含租户行：%s", tenantCode).isNotNull();
        assertThat(record.path("quotaStorageBytes").isMissingNode() || record.path("quotaStorageBytes").isNull())
                .as("quotaStorageBytes 缺失=AdminTenantView 未扩解析值字段（AMB-1 实现缺口锚点）").isFalse();
        return record.path("quotaStorageBytes").asLong();
    }

    /** 停用/启用（REQ-20261125-230807 信封化：成功 wire=Result 信封 code="0"；失败路径信封 code 断言用）。 */
    private JsonNode putStatus(long tenantId, String status) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("status", status);
        JsonNode envelope = callAsSuperAdmin(put(LIST_URL + "/" + tenantId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        if (envelope.path("success").asBoolean()) {
            assertThat(envelope.path("code").asText())
                    .as("状态端点成功应返回 Result 信封 code=\"0\"（REQ-20261125-230807 信封化）")
                    .isEqualTo("0");
        }
        return envelope;
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

    private static JsonNode findRecordByCode(JsonNode records, String tenantCode) {
        if (records == null || !records.isArray()) {
            return null;
        }
        for (JsonNode node : records) {
            if (tenantCode.equals(node.path("tenantCode").asText(null))) {
                return node;
            }
        }
        return null;
    }

    /** internal 清单宽容拆包（裸数组或 records 包裹；AMB-6 信封同形宽容）。 */
    private static boolean containsTenantCode(JsonNode data, String tenantCode) {
        JsonNode nodes = data.isArray() ? data : data.path("records");
        if (nodes.isMissingNode() || !nodes.isArray()) {
            nodes = data;
        }
        if (!nodes.isArray()) {
            return false;
        }
        for (JsonNode node : nodes) {
            if (tenantCode.equals(node.path("tenantCode").asText(null))) {
                return true;
            }
        }
        return false;
    }

    // ---------- HTTP 支撑（REQ-022954 先例口径） ----------

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
