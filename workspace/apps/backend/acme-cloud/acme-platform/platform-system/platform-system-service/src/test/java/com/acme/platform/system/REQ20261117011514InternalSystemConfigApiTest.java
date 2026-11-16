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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261117-011514 内部验证码策略查询（platform-system）服务域集成测试（Testcontainers PG，
 * 装配口径照抄 {@code REQ20261116230354SystemConfigApiIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖：AC-1 的 PG 迁移面（V16 后 sys_config 新列 captcha_enabled boolean NOT NULL DEFAULT false、
 * 省列插入落 false、迁移后既有行全为 false）；AC-5 的服务侧语义（internal 端点：有行→按行值、
 * 无行→缺省 false）；@IgnoreTenant 跨租户语义（A 租户上下文按入参 tenantCode 直查，得 B 租户行值）。</p>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>端点/入参按契约 API 节固定：GET /internal/v1/system-configs/captcha-policy?tenantId={code}，
 *       入参名 tenantId、取值为租户代码（sys_config.tenant_id 列存租户代码）。</li>
 *   <li>调用方上下文模拟 platform-auth 服务身份调用（Q-D08：内部令牌 userId=服务名），令牌租户 claim
 *       绑定为 tenant-a；@IgnoreTenant 行为以「tenant-a 上下文查 tenant-b 得 B 行值」行为断言证明。</li>
 *   <li>租户行以 jdbc 直插预置（同 REQ230354 seedConfigRow 手法）：tenant-a 省列插入（顺带验证 PG 列
 *       DEFAULT false），tenant-b 显式 captcha_enabled=true。</li>
 *   <li>internal 端点无 token / 空白入参的拒绝语义契约未钉死，不做断言（保持最小契约面）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req011514-internal-it-secret-0123456"
})
class REQ20261117011514InternalSystemConfigApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TENANT_A = "req011514-tenant-a";
    private static final String TENANT_B = "req011514-tenant-b";
    private static final String TENANT_NONE = "req011514-tenant-none";

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

    // ---------- AC-1（PG 迁移面）：V16 后列结构、省列默认值、既有行全 false ----------

    @Test
    void should_applyV16CaptchaDefaults_when_migrationApplied() {
        Map<String, Object> column = jdbcTemplate.queryForMap(
                "select data_type, is_nullable, column_default from information_schema.columns "
                        + "where table_name = 'sys_config' and column_name = 'captcha_enabled'");
        assertThat(column.get("data_type")).as("新列类型 boolean").isEqualTo("boolean");
        assertThat(column.get("is_nullable")).as("新列 NOT NULL").isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default"))).as("新列 DEFAULT false").isEqualTo("false");

        // 迁移内置默认关闭（确认记录①）：迁移后的既有行（含迁移自带的种子行）不得出现 true
        Long total = jdbcTemplate.queryForObject("select count(*) from sys_config", Long.class);
        Long disabled = jdbcTemplate.queryForObject(
                "select count(*) from sys_config where captcha_enabled = false", Long.class);
        assertThat(disabled).as("迁移后所有存量行 captcha_enabled 均为 false").isEqualTo(total);

        // 省列插入 → 落默认 false（存量写入路径不感知新列也不破约）
        seedRowWithoutCaptchaColumn(990_101L, TENANT_A, false, 60_000);
        assertThat(jdbcTemplate.queryForObject(
                "select captcha_enabled from sys_config where tenant_id = ?", Boolean.class, TENANT_A))
                .isFalse();
    }

    // ---------- AC-5（服务侧）＋ @IgnoreTenant：A 上下文按入参直查 B 租户行值 ----------

    @Test
    void should_returnQueriedTenantRowValue_when_contextTenantDiffers() {
        seedRowWithoutCaptchaColumn(990_201L, TENANT_A, false, 60_000);
        seedRowWithCaptcha(990_202L, TENANT_B, true, 900_000, true);

        JsonNode queried = callPolicy(TENANT_B);
        assertThat(queried.path("code").asText()).isEqualTo("0");
        assertThat(queried.path("data").path("captchaEnabled").asBoolean())
                .as("@IgnoreTenant：tenant-a 上下文按入参 tenantCode 直查，得 tenant-b 的行值（非本租户 false）")
                .isTrue();

        JsonNode own = callPolicy(TENANT_A);
        assertThat(own.path("code").asText()).isEqualTo("0");
        assertThat(own.path("data").path("captchaEnabled").asBoolean())
                .as("按入参查自身租户代码同样按行值返回").isFalse();
    }

    // ---------- AC-5（服务侧）：无配置行租户返回缺省 false ----------

    @Test
    void should_returnDefaultValue_when_internalQueryTenantWithoutRow() {
        seedRowWithCaptcha(990_301L, TENANT_A, false, 60_000, false);

        JsonNode none = callPolicy(TENANT_NONE);
        assertThat(none.path("code").asText()).isEqualTo("0");
        assertThat(none.path("data").path("captchaEnabled").asBoolean())
                .as("无行租户按缺省视图 false 返回").isFalse();
    }

    // ---------- 支撑 ----------

    /** 省列插入（captcha_enabled 交由 V16 列默认值填充）。 */
    private void seedRowWithoutCaptchaColumn(long id, String tenantId, boolean watermark, int idleTimeoutMs) {
        jdbcTemplate.update("""
                        insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                                created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, now(), 'req011514-it', now(), 'req011514-it', 0, false)
                        """,
                id, tenantId, watermark, idleTimeoutMs);
    }

    /** 显式携带 captcha_enabled 的插入。 */
    private void seedRowWithCaptcha(long id, String tenantId, boolean watermark, int idleTimeoutMs,
                                    boolean captchaEnabled) {
        jdbcTemplate.update("""
                        insert into sys_config (id, tenant_id, watermark_enabled, lock_idle_timeout_ms,
                                                captcha_enabled, created_at, created_by, updated_at, updated_by,
                                                version, deleted)
                        values (?, ?, ?, ?, ?, now(), 'req011514-it', now(), 'req011514-it', 0, false)
                        """,
                id, tenantId, watermark, idleTimeoutMs, captchaEnabled);
    }

    /** 模拟 platform-auth 服务身份调用（令牌租户 claim 固定 tenant-a，入参 tenantCode 可指向任意租户）。 */
    private JsonNode callPolicy(String tenantCode) {
        try {
            MvcResult result = mvc.perform(get("/internal/v1/system-configs/captcha-policy")
                            .queryParam("tenantId", tenantCode)
                            .header("X-Acme-Channel", "B")
                            .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                                    "platform-auth", TENANT_A, Channel.B, Set.of("super_admin"))))
                            .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                    new ClientContextDraft(null, "dev-1", "1.0")))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            return bodyOf(result);
        } catch (Exception ex) {
            throw new IllegalStateException("internal 策略查询失败：tenantCode=" + tenantCode, ex);
        }
    }

    private JsonNode bodyOf(MvcResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
