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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261117-025200 租户档案字段＋页脚版权（platform-system）服务域集成测试（Testcontainers PG，
 * 装配口径照抄 {@code REQ20261116230354SystemConfigApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-12（V17 迁移结构：sys_tenant 三列可空默认 NULL、sys_config footer_visible
 * NOT NULL DEFAULT false，含省列插入默认值）；AC-4（租户创建/更新三字段往返，可空语义）；
 * AC-8（config 页脚 PUT/GET 往返＋无行缺省视图）；by-code internal 端点（存在→视图、未知→
 * TENANT_NOT_FOUND）；public-view internal 端点（有行→行值 @IgnoreTenant 跨租户读、无行→缺省）；
 * AC-5 前半（登录下拉 listEnabled 排除已过期租户，确认记录③）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 AC-3（default 租户禁停拒绝）既有单元用例 {@code RoleAndTenantUseCaseTest
 *       #defaultTenantDisableRejected} 已覆盖（TENANT_DISABLE_DENIED 精确码），按委派单引用不重写。</li>
 *   <li>AMB-2 internal 端点入参名按契约层（system-api 已落盘）：by-code=tenantCode、
 *       public-view=tenantId（与 captcha-policy 同参名口径，取值为租户代码）；调用方身份模拟
 *       platform-auth 服务主体（Q-D08，REQ20261117011514InternalSystemConfigApiTest 先例），
 *       令牌租户 claim 绑 req025200-caller，@IgnoreTenant 行为以跨租户取值断言证明。</li>
 *   <li>AMB-3 by-code 未知租户：契约层 javadoc 钉「服务端返回 404 错误信封（TENANT_NOT_FOUND），
 *       不经 ContractResult 解码」，故断言 HTTP 404＋code=SystemErrorCodes.TENANT_NOT_FOUND。</li>
 *   <li>AMB-4 sys_tenant 直插列集按契约背景「id/tenant_code/tenant_name/status/remark＋通用列」
 *       ＋V17 三新列；status 以枚举名字面量 'ENABLED'/'DISABLED' 直插（Flex 枚举缺省序列化）。</li>
 *   <li>AMB-5 租户 PUT 乐观锁版本自增断言沿用 config PUT 先例（匹配版 +1）：version "0"→"1"。</li>
 *   <li>AMB-6 AC-5 前半（下拉排除过期）为委派单文件清单外的补齐用例：AC-5 双半句需成对回填，
 *       端点 /internal/v1/tenants 为既有契约（listEnabled），新增语义仅「排除已过期」。</li>
 * </ul>
 */
class REQ20261117025200TenantProfileFooterIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CALLER_TENANT = "req025200-caller";
    private static final String TENANT_TA = "req025200-ta";
    private static final String TENANT_TB = "req025200-tb";
    private static final String TENANT_NONE = "req025200-none";

    private static final OffsetDateTime EXPIRES_2027 = OffsetDateTime.parse("2027-01-01T00:00:00Z");
    private static final OffsetDateTime EXPIRES_2020 = OffsetDateTime.parse("2020-01-01T00:00:00Z");

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
        jdbcTemplate.update("delete from sys_config");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like 'req025200-%'");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_config");
        jdbcTemplate.update("delete from sys_tenant where tenant_code like 'req025200-%'");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-12：V17 迁移结构——三列可空 NULL、footer_visible NOT NULL DEFAULT false ----------

    @Test
    void should_applyV17TenantAndFooterColumns_when_migrationApplied() {
        Map<String, Object> contactName = columnMeta("sys_tenant", "contact_name");
        assertThat(contactName.get("data_type")).as("联系人列 varchar").isEqualTo("character varying");
        assertThat(((Number) contactName.get("character_maximum_length")).intValue()).isEqualTo(64);
        assertThat(contactName.get("is_nullable")).as("sys_tenant 三列可空（AC-12）").isEqualTo("YES");
        assertThat(contactName.get("column_default")).as("可空列无默认值").isNull();

        Map<String, Object> contactPhone = columnMeta("sys_tenant", "contact_phone");
        assertThat(contactPhone.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) contactPhone.get("character_maximum_length")).intValue()).isEqualTo(32);
        assertThat(contactPhone.get("is_nullable")).isEqualTo("YES");
        assertThat(contactPhone.get("column_default")).isNull();

        Map<String, Object> expiresAt = columnMeta("sys_tenant", "expires_at");
        assertThat(expiresAt.get("data_type")).as("有效期 timestamptz").isEqualTo("timestamp with time zone");
        assertThat(expiresAt.get("is_nullable")).isEqualTo("YES");
        assertThat(expiresAt.get("column_default")).isNull();

        Map<String, Object> footerCopyright = columnMeta("sys_config", "footer_copyright");
        assertThat(footerCopyright.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) footerCopyright.get("character_maximum_length")).intValue()).isEqualTo(255);
        assertThat(footerCopyright.get("is_nullable")).isEqualTo("YES");
        assertThat(footerCopyright.get("column_default")).isNull();

        Map<String, Object> footerVisible = columnMeta("sys_config", "footer_visible");
        assertThat(footerVisible.get("data_type")).isEqualTo("boolean");
        assertThat(footerVisible.get("is_nullable")).as("footer_visible NOT NULL（AC-12）").isEqualTo("NO");
        assertThat(String.valueOf(footerVisible.get("column_default")))
                .as("footer_visible DEFAULT false").isEqualTo("false");

        // 省列插入落默认值：sys_tenant 三列 NULL、sys_config footer_visible false
        jdbcTemplate.update("""
                insert into sys_tenant (id, tenant_code, tenant_name, status,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'ENABLED', now(), 'req025200-it', now(), 'req025200-it', 0, false)
                """, 925701L, "req025200-legacy", "存量租户");
        assertThat(queryTenant("contact_name", "req025200-legacy")).isNull();
        assertThat(queryTenant("contact_phone", "req025200-legacy")).isNull();
        assertThat(queryTenant("expires_at", "req025200-legacy")).isNull();

        jdbcTemplate.update("""
                insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, false, 60000, now(), 'req025200-it', now(), 'req025200-it', 0, false)
                """, 925702L, "req025200-legacy");
        assertThat(jdbcTemplate.queryForObject(
                "select footer_visible from sys_config where tenant_id = ?", Boolean.class, "req025200-legacy"))
                .as("存量写入路径不感知新列也不破约（页脚缺省不显示）").isFalse();
    }

    // ---------- AC-4：租户创建/更新三字段往返（可空语义：null=未配置/长期） ----------

    @Test
    void should_roundTripTenantProfileFields_when_createAndUpdateTenant() throws Exception {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("tenantCode", TENANT_TA);
        createBody.put("tenantName", "租户A");
        createBody.put("remark", "备注");
        createBody.put("contactName", "张三");
        createBody.put("contactPhone", "13800000000");
        createBody.put("expiresAt", "2027-01-01T00:00:00Z");

        JsonNode created = call(post("/admin/v1/system/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(createBody)));
        assertThat(created.path("code").asText()).isEqualTo("0");
        JsonNode view = created.path("data");
        assertThat(view.path("id").isTextual()).as("id wire 字符串（Long→String 模块）").isTrue();
        assertThat(view.path("id").asText()).isNotBlank();
        assertThat(view.path("tenantCode").asText()).isEqualTo(TENANT_TA);
        assertThat(view.path("tenantName").asText()).isEqualTo("租户A");
        assertThat(view.path("status").asText()).isEqualTo("ENABLED");
        assertThat(view.path("contactName").asText()).isEqualTo("张三");
        assertThat(view.path("contactPhone").asText()).isEqualTo("13800000000");
        assertThat(view.path("expiresAt").asText()).as("有效期 ISO-8601 字符串回显").isEqualTo("2027-01-01T00:00:00Z");
        assertThat(view.path("version").asText()).isEqualTo("0");

        // 更新：联系人改值、有效期置空（null=长期），version 乐观锁自增
        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("tenantName", "租户A-改");
        updateBody.put("remark", "备注-改");
        updateBody.put("contactName", "李四");
        updateBody.put("contactPhone", "13900000000");
        updateBody.put("expiresAt", null);
        updateBody.put("version", "0");
        JsonNode updated = call(put("/admin/v1/system/tenants/{tenantId}", view.path("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(updateBody)));
        assertThat(updated.path("code").asText()).isEqualTo("0");
        JsonNode updatedView = updated.path("data");
        assertThat(updatedView.path("contactName").asText()).isEqualTo("李四");
        assertThat(updatedView.path("contactPhone").asText()).isEqualTo("13900000000");
        assertThat(updatedView.path("expiresAt").isNull())
                .as("可空语义：置空有效期=长期有效").isTrue();
        assertThat(updatedView.path("version").asText()).as("乐观锁匹配版 +1（AMB-5）").isEqualTo("1");

        // jdbc 直连核对事实源
        assertThat(queryTenant("contact_name", TENANT_TA)).isEqualTo("李四");
        assertThat(queryTenant("expires_at", TENANT_TA)).isNull();
    }

    @Test
    void should_defaultProfileFieldsToNull_when_createWithoutOptionalProfileFields() throws Exception {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("tenantCode", TENANT_TB);
        createBody.put("tenantName", "租户B");

        JsonNode created = call(post("/admin/v1/system/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(createBody)));
        assertThat(created.path("code").asText()).isEqualTo("0");
        JsonNode view = created.path("data");
        assertThat(view.path("contactName").isNull()).as("未传联系人→null（可空语义）").isTrue();
        assertThat(view.path("contactPhone").isNull()).isTrue();
        assertThat(view.path("expiresAt").isNull()).as("未传有效期→长期").isTrue();
    }

    // ---------- AC-8：config 页脚 PUT/GET 往返＋无行缺省视图 ----------

    @Test
    void should_roundTripFooterFields_when_putSystemConfig() throws Exception {
        JsonNode created = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(false, true, 60_000, "© 2026 ACME", true, "0"))));
        assertThat(created.path("code").asText()).isEqualTo("0");
        assertThat(created.path("data").path("footerCopyright").asText()).isEqualTo("© 2026 ACME");
        assertThat(created.path("data").path("footerVisible").asBoolean()).isTrue();
        assertThat(created.path("data").path("version").asText()).isEqualTo("0");

        JsonNode read = call(get("/admin/v1/system/config"));
        assertThat(read.path("data").path("footerCopyright").asText()).isEqualTo("© 2026 ACME");
        assertThat(read.path("data").path("footerVisible").asBoolean()).isTrue();

        // 显隐关闭＋文案置空（可空）→ version +1
        JsonNode updated = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, false, 300_000, null, false, "0"))));
        assertThat(updated.path("code").asText()).isEqualTo("0");
        assertThat(updated.path("data").path("footerCopyright").isNull())
                .as("文案可空：置空后不渲染").isTrue();
        assertThat(updated.path("data").path("footerVisible").asBoolean()).isFalse();
        assertThat(updated.path("data").path("version").asText()).isEqualTo("1");

        JsonNode reloaded = call(get("/admin/v1/system/config"));
        assertThat(reloaded.path("data").path("footerCopyright").isNull()).isTrue();
        assertThat(reloaded.path("data").path("footerVisible").asBoolean()).isFalse();
    }

    @Test
    void should_returnFooterDefaults_when_noConfigRow() throws Exception {
        JsonNode defaults = call(get("/admin/v1/system/config"), "1", "req025200-empty-tenant", Set.of("super_admin"));
        assertThat(defaults.path("code").asText()).isEqualTo("0");
        assertThat(defaults.path("data").path("watermarkEnabled").asBoolean()).isTrue();
        assertThat(defaults.path("data").path("captchaEnabled").asBoolean()).isFalse();
        assertThat(defaults.path("data").path("lockIdleTimeoutMs").asInt()).isZero();
        assertThat(defaults.path("data").path("footerCopyright").isNull())
                .as("无行租户缺省视图 copyright=null（AC-8，未配置不显示）").isTrue();
        assertThat(defaults.path("data").path("footerVisible").asBoolean()).isFalse();
        assertThat(defaults.path("data").path("version").asText()).isEqualTo("0");
    }

    // ---------- by-code internal 端点：存在→视图；未知→TENANT_NOT_FOUND ----------

    @Test
    void should_returnTenantInternalView_when_internalQueryByCode() throws Exception {
        seedTenant(925711L, TENANT_TA, "租户A", "ENABLED", "张三", "13800000000", EXPIRES_2027);
        seedTenant(925712L, TENANT_TB, "租户B", "ENABLED", null, null, null);

        JsonNode byCode = callInternal(get("/internal/v1/tenants/by-code").queryParam("tenantCode", TENANT_TA));
        assertThat(byCode.path("code").asText()).isEqualTo("0");
        assertThat(byCode.path("data").path("tenantCode").asText()).isEqualTo(TENANT_TA);
        assertThat(byCode.path("data").path("tenantName").asText()).isEqualTo("租户A");
        assertThat(byCode.path("data").path("status").asText()).isEqualTo("ENABLED");
        assertThat(byCode.path("data").path("expiresAt").asText())
                .as("有效期 timestamptz → ISO-8601 字符串").isEqualTo("2027-01-01T00:00:00Z");

        JsonNode noExpiry = callInternal(get("/internal/v1/tenants/by-code").queryParam("tenantCode", TENANT_TB));
        assertThat(noExpiry.path("code").asText()).isEqualTo("0");
        assertThat(noExpiry.path("data").path("expiresAt").isNull()).as("可空有效期=长期").isTrue();
    }

    @Test
    void should_rejectUnknownTenant_when_internalQueryByCode() throws Exception {
        MvcResult result = performInternal(get("/internal/v1/tenants/by-code").queryParam("tenantCode", TENANT_NONE));
        assertThat(result.getResponse().getStatus())
                .as("未知 code → 404 语义（契约 §API；Feign 侧错误解码为 BizException）").isEqualTo(404);
        assertThat(bodyOf(result).path("code").asText())
                .as("TENANT_NOT_FOUND 精确码（SystemErrorCodes 06 段）")
                .isEqualTo(SystemErrorCodes.TENANT_NOT_FOUND);
    }

    // ---------- public-view internal 端点：有行→行值（@IgnoreTenant）；无行→缺省 ----------

    @Test
    void should_returnFooterView_when_internalPublicViewQuery() throws Exception {
        seedConfigRow(925721L, TENANT_TA, false, 60_000, "© 2026 ACME", true);

        JsonNode view = callInternal(get("/internal/v1/system-configs/public-view").queryParam("tenantId", TENANT_TA));
        assertThat(view.path("code").asText()).isEqualTo("0");
        assertThat(view.path("data").path("footerCopyright").asText())
                .as("@IgnoreTenant：caller 上下文按入参 tenantId 直查，得 req025200-ta 的行值").isEqualTo("© 2026 ACME");
        assertThat(view.path("data").path("footerVisible").asBoolean()).isTrue();
    }

    @Test
    void should_returnFooterDefaults_when_internalPublicViewNoRow() throws Exception {
        seedConfigRow(925722L, TENANT_TA, false, 60_000, "© 2026 ACME", true);

        JsonNode none = callInternal(get("/internal/v1/system-configs/public-view").queryParam("tenantId", TENANT_NONE));
        assertThat(none.path("code").asText()).isEqualTo("0");
        assertThat(none.path("data").path("footerCopyright").isNull())
                .as("无行租户缺省视图（确认记录⑤：未配置不显示）").isTrue();
        assertThat(none.path("data").path("footerVisible").asBoolean()).isFalse();
    }

    // ---------- AC-5 前半：登录下拉 listEnabled 排除已过期租户（确认记录③） ----------

    @Test
    void should_excludeExpiredTenant_when_listEnabled() throws Exception {
        seedTenant(925731L, "req025200-live", "存活租户", "ENABLED", null, null, EXPIRES_2027);
        seedTenant(925732L, "req025200-long", "长期租户", "ENABLED", null, null, null);
        seedTenant(925733L, "req025200-expired", "过期租户", "ENABLED", null, null, EXPIRES_2020);
        seedTenant(925734L, "req025200-off", "停用租户", "DISABLED", null, null, EXPIRES_2027);

        JsonNode list = callInternal(get("/internal/v1/tenants"));
        assertThat(list.path("code").asText()).isEqualTo("0");
        // V2 基线种子的 default 租户（启用未过期）同样在列——断言锁定 req025200-* 四态过滤语义
        assertThat(tenantCodesOf(list))
                .as("下拉仅返回启用且未过期租户：过期/停用被排除，启用未过期（含种子 default）保留")
                .contains("default", "req025200-live", "req025200-long")
                .doesNotContain("req025200-expired", "req025200-off");
    }

    // ---------- 支撑 ----------

    private Map<String, Object> columnMeta(String table, String column) {
        return jdbcTemplate.queryForMap(
                "select data_type, character_maximum_length, is_nullable, column_default "
                        + "from information_schema.columns where table_name = ? and column_name = ?",
                table, column);
    }

    private Object queryTenant(String column, String tenantCode) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from sys_tenant where tenant_code = ?", Object.class, tenantCode);
    }

    private static List<String> tenantCodesOf(JsonNode body) {
        JsonNode data = body.path("data");
        JsonNode records = data.isArray() ? data : data.path("records");
        return StreamSupport.stream(records.spliterator(), false)
                .map(item -> item.path("tenantCode").asText())
                .toList();
    }

    private void seedTenant(long id, String tenantCode, String tenantName, String status,
                            String contactName, String contactPhone, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                insert into sys_tenant (id, tenant_code, tenant_name, status, remark,
                                        contact_name, contact_phone, expires_at,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, null, ?, ?, ?, now(), 'req025200-it', now(), 'req025200-it', 0, false)
                """, id, tenantCode, tenantName, status, contactName, contactPhone, expiresAt);
    }

    private void seedConfigRow(long id, String tenantId, boolean watermark, int idleTimeoutMs,
                               String footerCopyright, boolean footerVisible) {
        jdbcTemplate.update("""
                insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                        footer_copyright, footer_visible,
                                        created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, ?, ?, ?, now(), 'req025200-it', now(), 'req025200-it', 0, false)
                """, id, tenantId, watermark, idleTimeoutMs, footerCopyright, footerVisible);
    }

    private static Map<String, Object> configBody(boolean watermarkEnabled, boolean captchaEnabled,
                                                  int lockIdleTimeoutMs, String footerCopyright,
                                                  boolean footerVisible, String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("watermarkEnabled", watermarkEnabled);
        body.put("captchaEnabled", captchaEnabled);
        body.put("lockIdleTimeoutMs", lockIdleTimeoutMs);
        body.put("footerCopyright", footerCopyright);
        body.put("footerVisible", footerVisible);
        body.put("version", version);
        return body;
    }

    /** 管理端调用（操作者身份经内部令牌，默认超管；REQ230354 先例口径）。 */
    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return call(request, "1", "default", Set.of("super_admin"));
    }

    private JsonNode call(AbstractMockHttpServletRequestBuilder<?> request,
                          String userId, String tenantId, Set<String> roles) throws Exception {
        MvcResult result = perform(request, userId, tenantId, roles);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return bodyOf(result);
    }

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request,
                              String userId, String tenantId, Set<String> roles) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject(userId, tenantId, Channel.B, roles)))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    /** platform-auth 服务主体调用（Q-D08，AMB-2）：internal 端点业务语义恒 HTTP 200 信封。 */
    private JsonNode callInternal(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = performInternal(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return bodyOf(result);
    }

    private MvcResult performInternal(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                "platform-auth", CALLER_TENANT, Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
