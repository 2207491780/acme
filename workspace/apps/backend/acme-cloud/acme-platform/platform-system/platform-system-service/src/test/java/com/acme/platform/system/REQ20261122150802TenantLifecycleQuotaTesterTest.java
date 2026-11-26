package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261122-150802（TENANT-4）tester 补缺口集成测试（Testcontainers PG，装配口径照
 * {@code REQ20261122150802TenantLifecycleQuotaIntegrationTest}/REQ20261122102954 先例）。
 *
 * <p>分工边界：开发侧 {@code REQ20261122150802TenantLifecycleQuotaIntegrationTest} 已充分覆盖
 * AC-1（V31 三变更+幂等）、AC-2/AC-3 三级链主路径、AC-4 派生数据面主路径、AC-5 聚合主路径、
 * AC-8 既有面回归、AC-9 级联正交。本类<b>只补缺口，不重复主路径</b>：</p>
 * <ul>
 *   <li>AC-2 缺口：租户覆盖的<b>二次覆盖（upsert）</b>——PUT quota 两次写不产生重复行
 *       （sys_tenant_quota.tenant_id uk 语义），新值生效。</li>
 *   <li>AC-4 缺口：宽限期<b>清除回落</b>——update graceUntil=null 后，expires 已过租户从登录
 *       下拉数据源回落为排除（派生态实时重算，无定时任务）；对照行：expires 未过+grace 已配置
 *       仍保留（宽限仅作用于已过期的派生输入）。</li>
 *   <li>AC-5 缺口：<b>空租户零值边界</b>——无文件无额外用户时 storageBytes=0、userCount=1
 *       （仅开通初始管理员）。</li>
 * </ul>
 *
 * <p>口径沿用开发类 AMB 标注：AMB-1 wire 字段名 graceUntil/quotaStorageBytes；AMB-2 平台缺省
 * 1GiB；AMB-3 STORAGE_QUOTA_EXCEEDED 上传触达闭环归 platform-infra（本类与开发类均不覆盖，
 * 待 TL 裁决是否补 infra IT）；AMB-4 登录闸门在 platform-auth，本类以 platform-system 派生
 * 数据面承载；AMB-5 sys_file 以 infra V2 同列集自举（replay-safe）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-tenant-lifecycle-tester-secret-01"
})
class REQ20261122150802TenantLifecycleQuotaTesterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OPERATOR = "req150802t-it";

    private static final String LIST_URL = "/admin/v1/system/tenants";

    private static final String INTERNAL_LIST_URL = "/internal/v1/tenants";

    /** 夹具前缀（与开发类 req150802-* 隔离；teardown 按前缀物理清理）。 */
    private static final String FIXTURE_PREFIX = "req150802t";

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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        // 共享环境脏数据 teardown：按夹具前缀物理清理（先旁表再主表）
        // sys_file 归 platform-infra 数据域：仅 bootstrap 建表后才存在，清理先判表存在
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
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- AC-2 缺口：租户覆盖二次覆盖（upsert，uk 不产生重复行） ----------

    @Test
    void should_overwrite_existing_override_in_place_when_quota_put_twice() throws Exception {
        String code = createTenantOk(FIXTURE_PREFIX + "-q-upsert");
        long tenantId = tenantIdByCode(code);

        // 首次覆盖 9999 → 解析 9999
        assertThat(putQuota(tenantId, 9999L).path("success").asBoolean()).isTrue();
        assertThat(resolvedQuotaOf(code)).as("首次覆盖生效").isEqualTo(9999L);

        // 二次覆盖 8888 → 解析切换到新值（upsert，非追加）
        assertThat(putQuota(tenantId, 8888L).path("success").asBoolean()).as("二次覆盖应成功").isTrue();
        assertThat(resolvedQuotaOf(code)).as("二次覆盖后解析=新覆盖值").isEqualTo(8888L);

        Integer rowCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant_quota where tenant_id = ?", Integer.class, tenantId);
        assertThat(rowCount).as("tenant_id uk 语义：覆盖重复写不产生重复行（upsert 单行）").isEqualTo(1);
    }

    // ---------- AC-4 缺口：宽限期清除回落 + 宽限仅作用于已过期派生输入 ----------

    @Test
    void should_fall_back_to_expired_exclusion_when_grace_until_cleared_and_keep_future_expiry_tenant()
            throws Exception {
        Instant now = Instant.now();

        // 目标行：expires 已过 + grace 未过 → 宽限内保留（前置）
        String graceCode = createTenantOk(FIXTURE_PREFIX + "-grace");
        updateLifecycle(graceCode, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
        assertThat(containsInLoginList(graceCode))
                .as("前置：宽限期内租户保留在登录下拉数据源（确认②）").isTrue();

        // 对照行：expires 未过 + grace 已配置 → 照常保留（宽限不影响未过期派生）
        String futureCode = createTenantOk(FIXTURE_PREFIX + "-future");
        updateLifecycle(futureCode, now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(3)));
        assertThat(containsInLoginList(futureCode))
                .as("expires 未过+grace 已配置：照常保留（宽限仅作用于已过期输入）").isTrue();

        // 清除宽限：update graceUntil=null → expires 已过租户派生回落为过期，排除
        updateLifecycle(graceCode, now.minus(Duration.ofDays(1)), null);
        assertThat(containsInLoginList(graceCode))
                .as("graceUntil 清除后（expires 已过）应回落为派生过期排除（确认⑥派生实时重算）").isFalse();

        // 管理面回读：清除后 graceUntil=NULL（wire AMB-1）
        JsonNode record = listRecordByCode(graceCode);
        assertThat(record).as("list 应含目标租户行").isNotNull();
        assertThat(record.path("graceUntil").isNull() || record.path("graceUntil").isMissingNode())
                .as("清除后 graceUntil 回读应为 null").isTrue();

        // 对照行不受影响
        assertThat(containsInLoginList(futureCode)).as("对照行不受目标行清除影响").isTrue();
    }

    // ---------- AC-4/AC-9 交叉缺口：DISABLED 正交优先派生数据面（确认②「已停用正交优先」） ----------

    @Test
    void should_exclude_disabled_tenant_from_login_list_even_in_grace_when_status_orthogonal_priority()
            throws Exception {
        Instant now = Instant.now();

        // 前置：expires 已过 + grace 未过 → 宽限期内保留在登录下拉数据源（派生宽限可登录）
        String code = createTenantOk(FIXTURE_PREFIX + "-orth");
        updateLifecycle(code, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
        assertThat(containsInLoginList(code))
                .as("前置：宽限期内租户保留（确认②派生宽限）").isTrue();

        // 停用 → 正交优先：即使宽限期内也从登录下拉数据源排除
        assertThat(putStatus(tenantIdByCode(code), "DISABLED").path("success").asBoolean())
                .as("停用应成功").isTrue();
        assertThat(containsInLoginList(code))
                .as("已停用租户即使宽限期内也应排除（确认②DISABLED 正交优先于派生宽限）").isFalse();

        // 恢复启用 → 派生宽限恢复（expires 仍过、grace 仍未来 → 重新保留；正交合成可逆）
        assertThat(putStatus(tenantIdByCode(code), "ENABLED").path("success").asBoolean())
                .as("恢复启用应成功").isTrue();
        assertThat(containsInLoginList(code))
                .as("恢复启用后宽限派生应恢复保留（正交合成可逆）").isTrue();
    }

    // ---------- AC-5 缺口：空租户零值边界 ----------

    @Test
    void should_report_zero_storage_and_admin_only_user_count_when_usage_queried_on_empty_tenant() throws Exception {
        bootstrapSysFile();
        String code = initTenant(FIXTURE_PREFIX + "-empty");

        // 不插任何 sys_file、不插额外用户 → 仅开通初始管理员
        JsonNode usage = callAsSuperAdmin(get(LIST_URL + "/" + tenantIdByCode(code) + "/usage"));
        assertThat(usage.path("success").asBoolean()).as("usage 端点应成功：%s", usage).isTrue();
        JsonNode data = usage.path("data");
        assertThat(data.path("storageBytes").isMissingNode())
                .as("usage 应含 storageBytes（AMB-1 wire 锚点）").isFalse();
        assertThat(data.path("storageBytes").asLong()).as("无文件租户 storageBytes=0").isZero();
        assertThat(data.path("userCount").asLong()).as("无额外用户时 userCount=1（仅初始管理员）").isEqualTo(1L);
    }

    // ---------- 夹具装配（照开发类/租户域先例口径） ----------

    private String createTenantOk(String tenantCode) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " tester 夹具租户");
        body.putNull("parentId");
        JsonNode result = callAsSuperAdmin(post(LIST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean()).as("create 租户应成功：%s", result).isTrue();
        return tenantCode;
    }

    private String initTenant(String tenantCode) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("tenantCode", tenantCode);
            body.put("tenantName", tenantCode + " tester 开通租户");
            body.putNull("remark");
            body.put("adminUsername", tenantCode.replace('-', '_') + "_admin");
            body.put("adminNickname", tenantCode + " 管理员");
            // REQ-20261125-230807 确认③：templateCode HTTP 面必填（未命中模板=既有兜底行为，语义不变）
            body.put("templateCode", "fixture-unmatched-template");
            body.putNull("parentId");
            JsonNode result = callAsSuperAdmin(post(LIST_URL + "/init")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(body)));
            assertThat(result.path("success").asBoolean()).as("init 开通应成功：%s", result).isTrue();
        } catch (Exception e) {
            throw new IllegalStateException("init 开通失败", e);
        }
        return tenantCode;
    }

    /** 生命周期更新（expiresAt/graceUntil 可空=null 语义；其余字段按既有 update 全量口径）。 */
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
        JsonNode result = callAsSuperAdmin(put(LIST_URL + "/" + tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
        assertThat(result.path("success").asBoolean())
                .as("update 生命周期应成功：%s", result).isTrue();
    }

    private JsonNode putQuota(long tenantId, Long quotaStorageBytes) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        if (quotaStorageBytes == null) {
            body.putNull("quotaStorageBytes");
        } else {
            body.put("quotaStorageBytes", quotaStorageBytes);
        }
        // REQ-20261125-230807 信封化：成功 wire=Result 信封 code="0"（不再有空体合成口径）
        return JSON.readTree(performAsSuperAdmin(put(LIST_URL + "/" + tenantId + "/quota")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body))).getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    /** 停用/启用（REQ-20261125-230807 信封化：成功 wire=Result 信封 code="0"；失败路径信封 code 断言用）。 */
    private JsonNode putStatus(long tenantId, String status) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("status", status);
        JsonNode envelope = JSON.readTree(performAsSuperAdmin(put(LIST_URL + "/" + tenantId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body))).getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        if (envelope.path("success").asBoolean()) {
            assertThat(envelope.path("code").asText())
                    .as("状态端点成功应返回 Result 信封 code=\"0\"（REQ-20261125-230807 信封化）")
                    .isEqualTo("0");
        }
        return envelope;
    }

    /** 解析后有效配额（AdminTenantView.quotaStorageBytes，确认⑦）。 */
    private long resolvedQuotaOf(String tenantCode) throws Exception {
        JsonNode record = listRecordByCode(tenantCode);
        assertThat(record).as("list 应含租户行：%s", tenantCode).isNotNull();
        assertThat(record.path("quotaStorageBytes").isMissingNode() || record.path("quotaStorageBytes").isNull())
                .as("quotaStorageBytes 缺失=AdminTenantView 未扩解析值字段（AMB-1 实现缺口锚点）").isFalse();
        return record.path("quotaStorageBytes").asLong();
    }

    private JsonNode listRecordByCode(String tenantCode) throws Exception {
        JsonNode page = callAsSuperAdmin(get(LIST_URL).queryParam("page", "1").queryParam("size", "200"));
        assertThat(page.path("success").asBoolean()).as("list 应成功：%s", page).isTrue();
        JsonNode records = page.path("data").path("records");
        for (JsonNode node : records) {
            if (tenantCode.equals(node.path("tenantCode").asText(null))) {
                return node;
            }
        }
        return null;
    }

    /** 登录下拉数据源谓词（/internal/v1/tenants；裸数组或 records 包裹宽容，AMB-6）。 */
    private boolean containsInLoginList(String tenantCode) throws Exception {
        JsonNode loginList = callAsSuperAdmin(get(INTERNAL_LIST_URL));
        assertThat(loginList.path("success").asBoolean())
                .as("internal 租户清单应成功：%s", loginList).isTrue();
        JsonNode data = loginList.path("data");
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

    private long tenantIdByCode(String tenantCode) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from sys_tenant where tenant_code = ?", Long.class, tenantCode);
        assertThat(ids).as("夹具租户应已落库：%s", tenantCode).isNotEmpty();
        return ids.get(0);
    }

    // ---------- HTTP 支撑（REQ-022954/开发类同款口径） ----------

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = performAsSuperAdmin(request);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult performAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }
}
