package com.acme.platform.system;

import com.acme.common.context.Channel;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261116-230354 租户级系统配置（platform-system）服务域集成测试（Testcontainers PG，
 * 装配口径同 {@code REQ20261114120104PositionApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1 双租户 GET 隔离；AC-2 乐观锁（匹配 +1 / 陈旧业务失败）；
 * AC-3 无 system:config:update PUT → HTTP 403、GET 认证即可读；
 * AC-4 无行缺省视图 + 非法时长业务失败；V15 种子（权限点/菜单/超管绑定）。</p>
 *
 * <p>配置业务错误返回 HTTP 200 失败信封；未认证 401、权限拒绝 403、缺字段校验 400。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-config-it-secret-0123456789abc"
})
class REQ20261116230354SystemConfigApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PERMISSION_DENIED = "platform-system-07-0001";
    private static final String CONFIG_INVALID = "platform-system-12-0001";

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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        jdbcTemplate.update("delete from sys_config");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_config");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：租户 A/B 各持配置，GET 仅返回本租户值 ----------

    @Test
    void should_returnOnlyOwnTenantConfig_when_tenantsHoldDifferentRows() throws Exception {
        seedConfigRow(910_001L, "tenant-a", false, 60_000, 3);
        seedConfigRow(910_002L, "tenant-b", true, 900_000, 5);

        JsonNode a = call(get("/admin/v1/system/config"), "9001", "tenant-a", Set.of("super_admin"));
        assertThat(a.path("code").asText()).isEqualTo("0");
        assertThat(a.path("data").path("watermarkEnabled").asBoolean()).isFalse();
        // REQ-20261117-011514：种子行未带新列 → 迁移缺省 false
        assertThat(a.path("data").path("captchaEnabled").asBoolean()).isFalse();
        assertThat(a.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(60_000);
        assertThat(a.path("data").path("version").asText()).isEqualTo("3");

        JsonNode b = call(get("/admin/v1/system/config"), "9002", "tenant-b", Set.of("super_admin"));
        assertThat(b.path("code").asText()).isEqualTo("0");
        assertThat(b.path("data").path("watermarkEnabled").asBoolean()).isTrue();
        assertThat(b.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(900_000);
        assertThat(b.path("data").path("version").asText()).isEqualTo("5");

        // B 行不受 A 会话读取影响（jdbc 直连核对）
        assertThat(jdbcTemplate.queryForObject(
                "select lock_idle_timeout_ms from sys_config where tenant_id='tenant-b'", Integer.class))
                .isEqualTo(900_000);
    }

    // ---------- AC-2：乐观锁（upsert 建行 → 匹配 +1 → 陈旧拒绝） ----------

    @Test
    void should_bumpVersionOnMatchAndRejectStaleVersion_when_putConfig() throws Exception {
        // 无行缺省 version="0"：upsert 建行（created 行 version=0，沿用既有 create 口径）
        JsonNode created = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(false, true, 60_000, "0"))));
        assertThat(created.path("code").asText()).isEqualTo("0");
        assertThat(created.path("data").path("watermarkEnabled").asBoolean()).isFalse();
        assertThat(created.path("data").path("captchaEnabled").asBoolean()).isTrue();
        assertThat(created.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(60_000);
        assertThat(created.path("data").path("version").asText()).isEqualTo("0");

        // 匹配 version=0 修改三项 → 值更新且 version=1
        JsonNode updated = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, false, 300_000, "0"))));
        assertThat(updated.path("code").asText()).isEqualTo("0");
        assertThat(updated.path("data").path("watermarkEnabled").asBoolean()).isTrue();
        assertThat(updated.path("data").path("captchaEnabled").asBoolean()).isFalse();
        assertThat(updated.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(300_000);
        assertThat(updated.path("data").path("version").asText()).isEqualTo("1");

        // 再 PUT 携带旧 version=0 → 业务失败 code≠0，值不回改
        JsonNode stale = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(false, true, 0, "0"))));
        assertThat(stale.path("success").asBoolean()).isFalse();
        assertThat(stale.path("code").asText()).isNotEqualTo("0");

        JsonNode after = call(get("/admin/v1/system/config"));
        assertThat(after.path("data").path("watermarkEnabled").asBoolean()).isTrue();
        assertThat(after.path("data").path("captchaEnabled").asBoolean()).isFalse();
        assertThat(after.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(300_000);
        assertThat(after.path("data").path("version").asText()).isEqualTo("1");
    }

    // ---------- AC-3：无 system:config:update → PUT 403；GET 认证即可读 ----------

    @Test
    void should_rejectPutWith403AndAllowGet_when_roleLacksConfigUpdate() throws Exception {
        MvcResult rejected = perform(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(false, false, 60_000, "0"))),
                "9003", "default", Set.of("req230354-unbound-role"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(403);
        assertThat(bodyOf(rejected).path("code").asText()).isEqualTo(PERMISSION_DENIED);

        JsonNode read = call(get("/admin/v1/system/config"),
                "9003", "default", Set.of("req230354-unbound-role"));
        assertThat(read.path("code").asText()).isEqualTo("0");
    }

    // ---------- AC-4：无行缺省视图 + 非法时长业务失败 ----------

    @Test
    void should_returnDefaultsWhenNoRow_and_rejectInvalidIdleTimeout() throws Exception {
        JsonNode defaults = call(get("/admin/v1/system/config"));
        assertThat(defaults.path("code").asText()).isEqualTo("0");
        assertThat(defaults.path("data").path("watermarkEnabled").asBoolean()).isTrue();
        // REQ-20261117-011514：无行缺省视图验证码缺省关闭（AC-5 口径）
        assertThat(defaults.path("data").path("captchaEnabled").asBoolean()).isFalse();
        assertThat(defaults.path("data").path("lockIdleTimeoutMs").asInt()).isZero();
        assertThat(defaults.path("data").path("version").asText()).isEqualTo("0");

        JsonNode invalid = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, false, 123, "0"))));
        assertThat(invalid.path("success").asBoolean()).isFalse();
        assertThat(invalid.path("code").asText()).isEqualTo(CONFIG_INVALID);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_config", Long.class))
                .as("非法取值不得建行").isZero();

        // 缺字段 → 参数校验业务失败（code≠0）
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("lockIdleTimeoutMs", 0);
        missing.put("version", "0");
        MvcResult incomplete = perform(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(missing)), "1", "default", Set.of("super_admin"));
        assertThat(incomplete.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyOf(incomplete).path("code").asText()).isNotEqualTo("0");
    }

    @Test
    void should_return401_when_getWithoutToken() throws Exception {
        MvcResult result = mvc.perform(get("/admin/v1/system/config")
                .header("X-Acme-Channel", "B")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void should_acceptEveryAllowedIdleTimeout_when_putValidValues() throws Exception {
        String version = "0";
        for (int timeout : new int[]{0, 60_000, 300_000, 900_000}) {
            JsonNode updated = call(put("/admin/v1/system/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(configBody(true, false, timeout, version))));
            assertThat(updated.path("code").asText()).isEqualTo("0");
            assertThat(updated.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(timeout);
            version = updated.path("data").path("version").asText();
            JsonNode reloaded = call(get("/admin/v1/system/config"));
            assertThat(reloaded.path("data").path("lockIdleTimeoutMs").asInt()).isEqualTo(timeout);
        }
    }

    // ---------- REQ-20261117-180000：水印模板列口径 + upsert 往返 + 未知占位符拒绝 ----------

    @Test
    void should_roundTripWatermarkTemplate_and_rejectUnknownPlaceholder_when_putConfig() throws Exception {
        // V18 列口径：varchar(255) 可空无默认（存量行不回填=未配置）
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                select data_type, character_maximum_length, is_nullable, column_default
                from information_schema.columns
                where table_schema = 'public' and table_name = 'sys_config' and column_name = 'watermark_template'
                """);
        assertThat(column.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) column.get("character_maximum_length")).intValue()).isEqualTo(255);
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(column.get("column_default")).isNull();

        // 无行缺省视图 watermarkTemplate=null（仅用户名）
        JsonNode defaults = call(get("/admin/v1/system/config"));
        assertThat(defaults.path("data").path("watermarkTemplate").isNull()).isTrue();

        // upsert 建行带模板 → 回读一致。防方法序依赖加固：类内其他用例可能已 upsert 建行
        // （乐观锁 version 递增）——首次 upsert 以 GET 当前 version 驱动，避免钉死 "0" 撞 409 版本冲突
        String currentVersion = defaults.path("data").path("version").asText("0");
        String template = "{username} {date} {time}";
        JsonNode created = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, false, 0, template, currentVersion))));
        assertThat(created.path("code").asText()).isEqualTo("0");
        assertThat(created.path("data").path("watermarkTemplate").asText()).isEqualTo(template);
        JsonNode reloaded = call(get("/admin/v1/system/config"));
        assertThat(reloaded.path("data").path("watermarkTemplate").asText()).isEqualTo(template);
        String updatedVersion = reloaded.path("data").path("version").asText(currentVersion);

        // 未知占位符 → 业务失败 CONFIG_INVALID 同段新码，值不回改（以当前 version 驱动，排除 409 干扰）
        JsonNode invalid = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, false, 0, "{bogus}", updatedVersion))));
        assertThat(invalid.path("success").asBoolean()).isFalse();
        assertThat(invalid.path("code").asText()).isEqualTo("platform-system-12-0002");
        JsonNode after = call(get("/admin/v1/system/config"));
        assertThat(after.path("data").path("watermarkTemplate").asText()).isEqualTo(template);

        // 置空（null）→ 回到未配置口径（version 沿用当前值）
        JsonNode cleared = call(put("/admin/v1/system/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(configBody(true, false, 0, null, updatedVersion))));
        assertThat(cleared.path("code").asText()).isEqualTo("0");
        assertThat(cleared.path("data").path("watermarkTemplate").isNull()).isTrue();
    }

    // ---------- V15 种子：权限点 / 菜单 / 超管绑定 ----------

    @Test
    void should_seedConfigPermissionsMenuAndSuperAdminBindings_when_migrationApplied() {
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where id in (66, 67) order by id",
                String.class))
                .containsExactly("system:config:list", "system:config:update");
        assertThat(jdbcTemplate.queryForList(
                "select http_method || ' ' || http_path from sys_permission where id in (66, 67) order by id",
                String.class))
                .containsExactly("GET /admin/v1/system/config", "PUT /admin/v1/system/config");

        Long unbound = jdbcTemplate.queryForObject(
                "select count(*) from sys_permission p where p.id in (66, 67) "
                        + "and not exists (select 1 from sys_role_permission rp where rp.permission_id = p.id "
                        + "and rp.role_id = (select id from sys_role where role_code='super_admin' and tenant_id='default'))",
                Long.class);
        assertThat(unbound).as("V15 新权限点应全部绑定 super_admin").isZero();

        Map<String, Object> menu = jdbcTemplate.queryForMap("select * from sys_menu where id = 10");
        assertThat(((Number) menu.get("parent_id")).longValue()).isEqualTo(1L);
        assertThat(menu.get("menu_name")).isEqualTo("系统配置");
        assertThat(menu.get("path")).isEqualTo("/system/config");
        assertThat(menu.get("component")).isEqualTo("system/config/index");
        assertThat(menu.get("permission_code")).isEqualTo("system:config:list");
        assertThat(menu.get("icon")).isEqualTo("settings");
        assertThat(((Number) menu.get("sort_order")).intValue()).isEqualTo(7);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu where role_id = 1 and menu_id = 10", Long.class))
                .isEqualTo(1L);
    }

    // ---------- 支撑 ----------

    private void seedConfigRow(long id, String tenantId, boolean watermark, int idleTimeoutMs, long version) {
        jdbcTemplate.update("""
                        insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                                created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, now(), 'req230354-it', now(), 'req230354-it', ?, false)
                        """,
                id, tenantId, watermark, idleTimeoutMs, version);
    }

    private static Map<String, Object> configBody(boolean watermarkEnabled, boolean captchaEnabled,
                                                  int lockIdleTimeoutMs, String version) {
        return configBody(watermarkEnabled, captchaEnabled, lockIdleTimeoutMs, null, version);
    }

    private static Map<String, Object> configBody(boolean watermarkEnabled, boolean captchaEnabled,
                                                  int lockIdleTimeoutMs, String watermarkTemplate,
                                                  String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("watermarkEnabled", watermarkEnabled);
        body.put("captchaEnabled", captchaEnabled);
        body.put("lockIdleTimeoutMs", lockIdleTimeoutMs);
        body.put("footerCopyright", null);
        body.put("footerVisible", false);
        body.put("watermarkTemplate", watermarkTemplate);
        body.put("version", version);
        return body;
    }

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

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}