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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-233426（TENANT-5）平台运营视图 tester 集成测试（Testcontainers PG，装配口径照
 * {@code REQ20261122150802TenantLifecycleQuotaIntegrationTest} TENANT-4 先例；无 Docker 自动跳过——
 * 判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约 AC-5（§确认记录⑥、§验收标准；AC-1~4 见
 * {@code REQ20261122233426TenantInitTemplateTesterTest}）：</p>
 * <ul>
 *   <li>AC-5：GET /admin/v1/system/tenants/usage-overview @IgnoreTenant 实时聚合——
 *       totalTenants（jdbc 全量 oracle）/四态计数（基线差分 oracle：enabled+3/grace+1/expired+1/
 *       disabled+1，派生语义照 TENANT-4 确认②：宽限内=expires 过+grace 未过、宽限也过=expired、
 *       DISABLED 正交优先）/tenantUsages 字段形状（tenantId/tenantCode/tenantName/status/
 *       storageBytes/quotaBytes/quotaSource/userCount）+ storageBytes=未删 size 总和 +
 *       userCount（初始管理员+业务用户）+ quotaBytes=平台缺省 1GiB（无套餐无覆盖，
 *       TENANT-4 AMB-2 同源）/降序/topN=20 截断/调用方上下文绑定租户不收窄数据面（旁路证据）。</li>
 *   <li>坑 16 生效面：无 system:tenant:usage-overview 权限 → 403（既有 RBAC 翻译器口径，
 *       PERMISSION_DENIED 登记 403）——证明 V32 种子权限点真实挂接端点。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 quotaSource 取值枚举契约未钉死（PLATFORM_DEFAULT/PACKAGE/TENANT_OVERRIDE 命名猜测不写）——
 *       断言「字段存在且非空」；取值枚举待实现披露后补强。</li>
 *   <li>AMB-2 tenantUsages 行 status 取 raw（ENABLED/DISABLED）假设；无到期租户 raw=派生值同形不歧义。</li>
 *   <li>AMB-3 packageDistribution?（契约⑥可选项）本类不钉（可选项缺席合规）。</li>
 *   <li>AMB-4 sys_file 归 platform-infra 迁移域（TENANT-4 AMB-5 同口径）：system IT 库无此表，
 *       以 infra V2 同列集自举（CREATE TABLE IF NOT EXISTS，replay-safe）。</li>
 *   <li>依赖（backend 并行交付，映射表注明）：GET /admin/v1/system/tenants/usage-overview +
 *       UsageOverviewView 形状 + V32 种子权限点。实现未落盘时本类红灯=实现缺口锚点非用例缺陷
 *       （tester 交付口径，附原样输出归因 TL）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-tenant-overview-tester-secret-0123"
})
class REQ20261122233426UsageOverviewTesterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req233426o-it";

    private static final String TENANTS_URL = "/admin/v1/system/tenants";

    private static final String OVERVIEW_URL = "/admin/v1/system/tenants/usage-overview";

    /** 夹具租户码前缀（teardown 按前缀物理清理；与开发侧 *IntegrationTest 前缀隔离防撞名）。 */
    private static final String FIXTURE_PREFIX = "req233426o";

    /** 平台缺省配额（TENANT-4 背景钉死：StorageProperties.defaultBytes 缺省 1GiB，AMB-2 同源）。 */
    private static final long PLATFORM_DEFAULT_QUOTA = 1073741824L;

    /** sys_file 夹具行 id 基点（种子/雪花撞车区间之外，teardown 按 tenant_id 前缀清理）。 */
    private static final AtomicLong FILE_ID_SEQ = new AtomicLong(0x52334260000L);

    private static final AtomicLong USER_ID_SEQ = new AtomicLong(0x62334260000L);

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

    @MockitoBean
    private TenantSessionRevocationGateway revocationGateway;

    private MockMvc mvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 每用例唯一序号：租户码互不串扰。 */
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
        // 共享环境脏数据 teardown：按夹具前缀物理清理（先旁表再主表）
        // sys_file 归 platform-infra 数据域：仅 bootstrap 建表后才存在，清理先判表存在（AMB-4）
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select to_regclass('sys_file') is not null", Boolean.class))) {
            jdbcTemplate.update("delete from sys_file where tenant_id like ?", FIXTURE_PREFIX + "%");
        }
        jdbcTemplate.update(
                "delete from sys_tenant_quota where tenant_id in "
                        + "(select id from sys_tenant where tenant_code like ?)", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_config where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_permission where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_user where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role_menu where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_role where tenant_id like ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", FIXTURE_PREFIX + "%");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-5：聚合形状 + 四态计数 + 旁路 + 排序 ----------

    @Test
    void should_aggregate_status_counts_and_tenant_usage_bypass_when_overview_queried() throws Exception {
        bootstrapSysFile();

        // ① 基线快照（before）——差分 oracle 对种子水位漂移免疫
        JsonNode before = overviewAs("1", "default");
        assertThat(before.path("success").asBoolean()).as("usage-overview 应成功：%s", before).isTrue();
        long totalBefore = before.path("data").path("totalTenants").asLong();
        long enabledBefore = before.path("data").path("statusCounts").path("enabled").asLong();
        long graceBefore = before.path("data").path("statusCounts").path("grace").asLong();
        long expiredBefore = before.path("data").path("statusCounts").path("expired").asLong();
        long disabledBefore = before.path("data").path("statusCounts").path("disabled").asLong();

        Instant now = Instant.now();
        // 四态夹具（派生语义照 TENANT-4 确认②）：
        // F 未到期 → enabled；G 宽限内（expires 过/grace 未过）→ grace；
        // E 宽限也过 → expired；D 停用（无到期，正交优先不歧义）→ disabled
        String activeCode = createTenantOk(FIXTURE_PREFIX + "-ov-active");
        updateLifecycle(activeCode, now.plus(Duration.ofDays(2)), null);
        String graceCode = createTenantOk(FIXTURE_PREFIX + "-ov-grace");
        updateLifecycle(graceCode, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
        String expiredCode = createTenantOk(FIXTURE_PREFIX + "-ov-expired");
        updateLifecycle(expiredCode, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        String disabledCode = createTenantOk(FIXTURE_PREFIX + "-ov-disabled");
        assertThat(putStatus(tenantIdByCode(disabledCode), "DISABLED").path("success").asBoolean())
                .as("停用夹具应成功").isTrue();

        // 用量夹具：开通租户（初始管理员）+2 业务用户 + 未删文件 100+200（已删 500 不计入）
        String storageCode = initTenant(FIXTURE_PREFIX + "-ov-storage");
        seedUserInTenant(storageCode + "-u1", storageCode);
        seedUserInTenant(storageCode + "-u2", storageCode);
        insertSysFile(storageCode, 100L, false);
        insertSysFile(storageCode, 200L, false);
        insertSysFile(storageCode, 500L, true);
        // 小用量对照租户（简单 create，userCount=0，文件 100）——排序锚点
        String smallCode = createTenantOk(FIXTURE_PREFIX + "-ov-small");
        insertSysFile(smallCode, 100L, false);
        // 无关租户文件（不同前缀租户码不落 sys_tenant → 不进 totalTenants，但文件挂其码下不入聚合）
        insertSysFile("req233426o-stranger", 999L, false);

        // ② 聚合结果：totalTenants=+6、四态 enabled+3/grace+1/expired+1/disabled+1（差分 oracle）
        JsonNode after = overviewAs("1", "default");
        JsonNode data = after.path("data");
        assertThat(data.path("totalTenants").isMissingNode())
                .as("UsageOverviewView 应含 totalTenants（契约⑥ wire 锚点）").isFalse();
        assertThat(data.path("totalTenants").asLong())
                .as("totalTenants 应为全量租户数（before+6）").isEqualTo(totalBefore + 6);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant where deleted = false", Long.class))
                .as("jdbc 全量 oracle：totalTenants=库内未删租户数").isEqualTo(data.path("totalTenants").asLong());
        assertThat(data.path("statusCounts").path("enabled").asLong())
                .as("enabled 计数差分（active+storage+small）").isEqualTo(enabledBefore + 3);
        assertThat(data.path("statusCounts").path("grace").asLong())
                .as("grace 计数差分（宽限内派生，TENANT-4 确认②）").isEqualTo(graceBefore + 1);
        assertThat(data.path("statusCounts").path("expired").asLong())
                .as("expired 计数差分（宽限也过派生）").isEqualTo(expiredBefore + 1);
        assertThat(data.path("statusCounts").path("disabled").asLong())
                .as("disabled 计数差分（停用正交优先）").isEqualTo(disabledBefore + 1);

        // ③ tenantUsages 行形状 + 聚合正确性（storageBytes=未删总和/userCount/quotaBytes 平台缺省）
        JsonNode storage = usageRecordByCode(data.path("tenantUsages"), storageCode);
        assertThat(storage).as("tenantUsages 应含用量夹租户").isNotNull();
        for (String field : List.of("tenantId", "tenantCode", "tenantName", "status",
                "storageBytes", "quotaBytes", "quotaSource", "userCount")) {
            assertThat(storage.path(field).isMissingNode())
                .as("tenantUsages 行应含 " + field + "（契约⑥ wire 锚点）").isFalse();
        }
        assertThat(storage.path("storageBytes").asLong())
                .as("storageBytes=未删 size 总和（100+200；已删 500 不计入）").isEqualTo(300L);
        assertThat(storage.path("userCount").asLong())
                .as("userCount=租户内用户数（初始管理员+2）").isEqualTo(3L);
        assertThat(storage.path("quotaBytes").asLong())
                .as("quotaBytes=平台缺省 1GiB（无套餐无覆盖，TENANT-4 AMB-2 同源）")
                .isEqualTo(PLATFORM_DEFAULT_QUOTA);
        assertThat(storage.path("quotaSource").asText())
                .as("quotaSource 存在且非空（取值枚举 AMB-1 不钉）").isNotEmpty();
        assertThat(storage.path("status").asText()).as("行 status=ENABLED（无到期 raw=派生同形，AMB-2）")
                .isEqualTo("ENABLED");
        assertThat(storage.path("storageBytes").asLong())
                .as("无关租户文件 999 不混入（旁路校验）").isNotEqualTo(1299L);

        // ④ topN 内排序：storageBytes 全局非递增（契约⑥「按 storageBytes 降序」）
        JsonNode small = usageRecordByCode(data.path("tenantUsages"), smallCode);
        assertThat(small).as("tenantUsages 应含小用量对照租户").isNotNull();
        assertThat(small.path("storageBytes").asLong()).as("小用量对照租户聚合值").isEqualTo(100L);
        assertThat(small.path("userCount").asLong()).as("简单创建租户 userCount=0（无开通管理员）").isZero();
        assertThat(storageBytesInOrder(data.path("tenantUsages")))
                .as("tenantUsages 按 storageBytes 降序（契约⑥）")
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(indexOfCode(data.path("tenantUsages"), storageCode))
                .as("大用量租户应排在小用量之前（降序）")
                .isLessThan(indexOfCode(data.path("tenantUsages"), smallCode));

        // ⑤ @IgnoreTenant 旁路：调用方上下文绑定夹具租户，数据面不收窄（仍含 default 与全量计数）
        JsonNode bypassed = overviewAs("1", storageCode);
        assertThat(bypassed.path("success").asBoolean())
                .as("夹具租户上下文调用应成功（权限=种子点超管短路）：%s", bypassed).isTrue();
        assertThat(bypassed.path("data").path("totalTenants").asLong())
                .as("@IgnoreTenant 旁路：调用方租户上下文不限定聚合范围（确认⑥）")
                .isEqualTo(data.path("totalTenants").asLong());
        assertThat(bypassed.path("data").path("statusCounts").path("disabled").asLong())
                .as("旁路四态计数与 default 上下文一致").isEqualTo(data.path("statusCounts").path("disabled").asLong());
        assertThat(usageRecordByCode(bypassed.path("data").path("tenantUsages"), "default"))
                .as("旁路面仍含 default 租户数据（跨租户聚合证据）").isNotNull();
    }

    // ---------- AC-5：topN=20 截断 ----------

    @Test
    void should_cap_tenant_usages_at_top_twenty_by_storage_desc_when_many_tenants() throws Exception {
        bootstrapSysFile();

        // 25 个简单租户 + 独占文件量 1000..25000（基线租户 0 字节恒在其后）→ top20=6000..25000
        String smallestPresentCode = null;
        for (int i = 1; i <= 25; i++) {
            String code = createTenantOk(FIXTURE_PREFIX + "-top" + i);
            insertSysFile(code, 1000L * i, false);
            if (i == 6) {
                smallestPresentCode = code; // 6000：截断线上的最后一名
            }
        }

        JsonNode result = overviewAs("1", "default");
        assertThat(result.path("success").asBoolean()).as("usage-overview 应成功：%s", result).isTrue();
        JsonNode usages = result.path("data").path("tenantUsages");
        assertThat(usages.isArray()).as("tenantUsages 应为数组").isTrue();
        assertThat(usages.size()).as("topN=20 截断（契约⑥，25+候选取前 20）").isEqualTo(20);
        assertThat(storageBytesInOrder(usages))
                .as("topN 内仍按 storageBytes 降序").isSortedAccordingTo(Comparator.reverseOrder());
        for (int i = 1; i <= 5; i++) {
            assertThat(usageRecordByCode(usages, FIXTURE_PREFIX + "-top" + i))
                    .as("最小 5 个租户（1000..5000）应被 topN 截断排除").isNull();
        }
        assertThat(usageRecordByCode(usages, smallestPresentCode))
                .as("截断线上租户（6000）应保留").isNotNull();
        assertThat(usageRecordByCode(usages, FIXTURE_PREFIX + "-top25"))
                .as("最大租户（25000）应居首").isNotNull();
        assertThat(usages.get(0).path("tenantCode").asText()).as("首行=storageBytes 最大租户")
                .isEqualTo(FIXTURE_PREFIX + "-top25");
    }

    // ---------- 坑 16 生效面：无 system:tenant:usage-overview 权限 → 403 ----------

    @Test
    void should_reject_overview_without_seed_permission_per_existing_rbac() throws Exception {
        // 无权限装配（MessageApi/TenantPackage 先例）：令牌角色无 sys_role 行 → 权限解析空集
        Set<String> noPermissionRoles = Set.of(FIXTURE_PREFIX + "_unbound_role");
        MvcResult result = performAs("1", "default", noPermissionRoles, get(OVERVIEW_URL));
        assertThat(result.getResponse().getStatus())
                .as("无 system:tenant:usage-overview 权限应 403（PERMISSION_DENIED 登记 403，V32 种子点生效面）")
                .isEqualTo(403);
        JsonNode body = bodyOf(result);
        assertThat(body.path("success").asBoolean()).as("403 以 Result 信封表达").isFalse();
        assertThat(body.path("code").asText()).as("错误码=既有 PERMISSION_DENIED 三段式码")
                .isEqualTo(SystemErrorCodes.PERMISSION_DENIED);
    }

    // ---------- 夹具装配（TENANT-4 先例口径） ----------

    /** system IT 库无 sys_file（platform-infra 迁移域，AMB-4）：以 infra V2 同列集自举（幂等守卫）。 */
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
                FILE_ID_SEQ.incrementAndGet(), tenantCode + "/" + size + "/" + deleted,
                "f-" + size + "-" + FILE_ID_SEQ.get(), size, tenantCode,
                OPERATOR, OPERATOR, deleted);
    }

    private void seedUserInTenant(String username, String tenantCode) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, 'ENABLED', false, now(), ?, now(), ?, 0, false)
                        """,
                USER_ID_SEQ.incrementAndGet(), tenantCode, username, username, OPERATOR, OPERATOR);
    }

    private String createTenantOk(String tenantCode) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 运营夹具租户");
        body.putNull("parentId");
        JsonNode result = callAsSuperAdmin(post(TENANTS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean()).as("create 租户应成功：%s", result).isTrue();
        return tenantCode;
    }

    /** init 开通（成功信封断言），返回租户码（TENANT-4 先例）。 */
    private String initTenant(String tenantCode) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 开通夹具租户");
        body.putNull("remark");
        body.put("adminUsername", tenantCode.replace('-', '_') + "_admin");
        body.put("adminNickname", tenantCode + " 管理员");
        body.putNull("parentId");
        JsonNode result = callAsSuperAdmin(post(TENANTS_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean()).as("init 开通应成功：%s", result).isTrue();
        assertThat(result.path("code").asText()).isEqualTo("0");
        return tenantCode;
    }

    /** 生命周期更新（expiresAt/graceUntil 可空=null 语义；其余字段按既有 update 全量口径，TENANT-4 同款）。 */
    private void updateLifecycle(String tenantCode, Instant expiresAt, Instant graceUntil) throws Exception {
        long tenantId = tenantIdByCode(tenantCode);
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantName", jdbcTemplate.queryForObject(
                "select tenant_name from sys_tenant where id = ?", String.class, tenantId));
        body.putNull("remark");
        body.putNull("contactName");
        body.putNull("contactPhone");
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        if (graceUntil == null) {
            body.putNull("graceUntil");
        } else {
            body.put("graceUntil", graceUntil.toString());
        }
        body.put("version", jdbcTemplate.queryForObject(
                "select version from sys_tenant where id = ?", Long.class, tenantId));
        body.putNull("packageId");
        body.putNull("parentId");
        JsonNode result = callAsSuperAdmin(put(TENANTS_URL + "/" + tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean())
                .as("update 生命周期应成功：%s", result).isTrue();
    }

    /** 停用/启用（成功路径 emptyAsSuccess 双口径兼容，TENANT-4 同款）。 */
    private JsonNode putStatus(long tenantId, String status) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("status", status);
        MvcResult result = performAs("1", "default", Set.of("super_admin"),
                put(TENANTS_URL + "/" + tenantId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)));
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (content.isBlank()) {
            ObjectNode ok = JSON.createObjectNode();
            ok.put("success", true);
            ok.put("code", "0");
            return ok;
        }
        return JSON.readTree(content);
    }

    private long tenantIdByCode(String tenantCode) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from sys_tenant where tenant_code = ?", Long.class, tenantCode);
        assertThat(ids).as("夹具租户应已落库：%s", tenantCode).isNotEmpty();
        return ids.get(0);
    }

    // ---------- 查询支撑 ----------

    private JsonNode overviewAs(String userId, String tenant) throws Exception {
        return callAs(userId, tenant, get(OVERVIEW_URL));
    }

    private static JsonNode usageRecordByCode(JsonNode usages, String tenantCode) {
        if (!usages.isArray()) {
            return null;
        }
        for (JsonNode node : usages) {
            if (tenantCode.equals(node.path("tenantCode").asText(null))) {
                return node;
            }
        }
        return null;
    }

    private static int indexOfCode(JsonNode usages, String tenantCode) {
        for (int i = 0; i < usages.size(); i++) {
            if (tenantCode.equals(usages.get(i).path("tenantCode").asText(null))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static List<Long> storageBytesInOrder(JsonNode usages) {
        List<Long> values = new ArrayList<>();
        usages.forEach(node -> values.add(node.path("storageBytes").asLong()));
        return values;
    }

    // ---------- HTTP 支撑（TENANT-4 先例口径） ----------

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return callAs("1", "default", request);
    }

    private JsonNode callAs(String userId, String tenant, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = performAs(userId, tenant, Set.of("super_admin"), request);
        return bodyOf(result);
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
