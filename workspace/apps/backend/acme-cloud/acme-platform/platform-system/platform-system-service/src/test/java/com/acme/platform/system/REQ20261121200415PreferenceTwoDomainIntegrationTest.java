package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * REQ-20261121-200415 偏好两域持久化（THEME-3，platform-system /admin/v1/system/me/preferences）
 * 服务域集成测试（Testcontainers PG+Redis，装配口径照抄 {@code REQ20261119082531ProfilePreferenceIntegrationTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖（契约确认记录②③④⑩）：AC-1 兼容旧 PUT {messagePush}（themePreference 域不被触碰）；
 * AC-2 两域按域 upsert（仅 themePreference → messagePush 保持原值；两域同 PUT → GET 回显；
 * 两域皆 null → 400）；AC-3 themePreference 校验拒绝矩阵 fail-closed（未知顶层键/未知偏好键/
 * 坏枚举/坏 hex/schemaVersion 非整数/extensions 超限 → 400 校验错误码且原数据不动；合法
 * extensions 透传）；AC-4 未设行 GET 缺省（messagePush=false + themePreference=null，
 * 后端不内置主题默认值）。另含 accent 预设白名单锚点（TL 通告：12 预设权威表新增
 * emerald/sky/coral/ruby/bronze/slate 六键——emerald 正例透传；品牌色 teal 非预设键负例拒绝）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>T3-1 校验拒绝错误码：非法 themePreference → HTTP 400 + 既有校验错误码信封
 *       （REQ-082531 终稿口径、不新增段）。精确段位是实现面非契约面，断言用正则
 *       {@code SYS-01-\d{4}}（既有 SystemError 段）收紧到「沿用既有段、code≠0、success=false」。</li>
 *   <li>T3-2 合法 blob 最小集：偏好键以 core 实锚字段表为准（theme.ts:25-38，t3-backend
 *       校验器对齐源；契约③文本 motion/radiusScale/semanticColorOverrides/followSystem
 *       键名漂移待 TL 回填契约）。取无歧义子集（mode=dark、accent=cyan 默认预设、
 *       customAccent 六位 hex、density=comfortable、motionEnabled=boolean）；
 *       12 accent 预设全集与 radius/semanticColors 取值域不在本类构造取值。</li>
 *   <li>T3-3 extensions 上界：单键 &gt;16KB / 总 &gt;64KB 拒绝。断言取明显超限值
 *       （单键 20_001 字符 / 六键各 12_500 字符），不贴边界（字节计量口径非契约面）。</li>
 *   <li>T3-4 fail-closed「原数据不动」：种子行经 PUT 两域 API 建立成功后（upsert 路径
 *       AC-2 覆盖），对被拒绝 PUT 前后 GET 两次回显做整树相等断言；不走 JDBC 直插种子
 *       （两域在 prefs jsonb 内部存储形态非契约面，不猜）。</li>
 *   <li>T3-5 偏好主体无需 sys_user 行（按 user_id 定位存储，REQ-082531 同口径）。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req200415-prefs-secret-0123456789ab"
})
class REQ20261121200415PreferenceTwoDomainIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 偏好主体 A（AC-1 兼容旧形状主用例）。 */
    private static final long PREF_USER_A = 904011L;

    /** 偏好主体 B（AC-2 按域 upsert 主用例）。 */
    private static final long PREF_USER_B = 904012L;

    /** 偏好主体 C（AC-3 校验矩阵主用例）。 */
    private static final long PREF_USER_C = 904013L;

    /** 偏好主体 D（AC-4 未设行缺省，全用例不写行）。 */
    private static final long PREF_USER_D = 904014L;

    /**
     * 合法 themePreference blob（T3-2 最小集；schemaVersion=3 对齐 core PREFERENCES_VERSION；
     * 偏好键以 core 实锚字段表为准——motionEnabled，契约③文本 motion 键名漂移待 TL 回填）。
     */
    private static final String VALID_BLOB = """
            {"schemaVersion": 3, "preferences": {"mode": "dark", "accent": "cyan", "customAccent": "#5aa7a0", "density": "comfortable", "motionEnabled": true}}
            """;

    /** 另一合法 blob（用于「不被触碰/保持原值」的差异比对）。 */
    private static final String OTHER_BLOB = """
            {"schemaVersion": 3, "preferences": {"mode": "light", "accent": "blue", "motionEnabled": false}}
            """;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** Redis 容器：上下文装配需可用（站内信总线语义，REQ-082531 同口径）。 */
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_user_preference");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：兼容旧 PUT {messagePush}，themePreference 域不被触碰 ----------

    @Test
    void putPreferencesLegacyMessagePushOnlyLeavesThemePreferenceUntouched() throws Exception {
        // given: 主体 A 已有两域数据
        assertThat(putPrefs(PREF_USER_A, """
                {"messagePush": true, "themePreference": %s}
                """.formatted(VALID_BLOB.strip())).getResponse().getStatus()).isEqualTo(200);

        // when: 旧形状 PUT（仅 messagePush 域）
        JsonNode putBody = readBody(putPrefs(PREF_USER_A, "{\"messagePush\": false}"));

        // then: 成功，messagePush 更新，themePreference 域原值不被触碰
        assertThat(putBody.path("code").asText()).isEqualTo("0");
        assertThat(putBody.path("data").path("messagePush").asBoolean()).isFalse();
        assertThat(putBody.path("data").path("themePreference"))
                .as("旧形状 PUT 响应中 themePreference 应保持原 blob（确认记录②）")
                .isEqualTo(JSON.readTree(VALID_BLOB));

        JsonNode echoed = readBody(callAs(PREF_USER_A, get("/admin/v1/system/me/preferences")));
        assertThat(echoed.path("data").path("messagePush").asBoolean()).isFalse();
        assertThat(echoed.path("data").path("themePreference"))
                .as("旧形状 PUT 后 GET 回显 themePreference 应保持原值")
                .isEqualTo(JSON.readTree(VALID_BLOB));
    }

    @Test
    void putPreferencesLegacyMessagePushOnFreshUserKeepsThemePreferenceNull() throws Exception {
        // given: 主体 D 未设任何偏好行；when: 旧形状 PUT
        JsonNode putBody = readBody(putPrefs(PREF_USER_D, "{\"messagePush\": true}"));

        // then: messagePush 生效，themePreference 不被触碰（仍 null，不误建默认主题）
        assertThat(putBody.path("code").asText()).isEqualTo("0");
        assertThat(putBody.path("data").path("messagePush").asBoolean()).isTrue();
        assertThat(putBody.path("data").path("themePreference").isNull())
                .as("旧形状 PUT 不得给 themePreference 写入任何值（确认记录②④）").isTrue();

        JsonNode echoed = readBody(callAs(PREF_USER_D, get("/admin/v1/system/me/preferences")));
        assertThat(echoed.path("data").path("messagePush").asBoolean()).isTrue();
        assertThat(echoed.path("data").path("themePreference").isNull()).isTrue();
    }

    // ---------- AC-2：两域按域 upsert ----------

    @Test
    void putPreferencesBothDomainsUpsertsAndEchoesOnGet() throws Exception {
        // when: 两域同 PUT（主体 B 首次写即插入单行）
        JsonNode putBody = readBody(putPrefs(PREF_USER_B, """
                {"messagePush": true, "themePreference": %s}
                """.formatted(VALID_BLOB.strip())));

        // then: PUT 响应与 GET 回显两域
        assertThat(putBody.path("code").asText()).isEqualTo("0");
        assertThat(putBody.path("data").path("messagePush").asBoolean()).isTrue();
        assertThat(putBody.path("data").path("themePreference")).isEqualTo(JSON.readTree(VALID_BLOB));

        JsonNode echoed = readBody(callAs(PREF_USER_B, get("/admin/v1/system/me/preferences")));
        assertThat(echoed.path("data").path("messagePush").asBoolean()).isTrue();
        assertThat(echoed.path("data").path("themePreference")).isEqualTo(JSON.readTree(VALID_BLOB));

        // 重复 PUT（upsert 幂等，本人单行不新增）
        assertThat(putPrefs(PREF_USER_B, "{\"messagePush\": false}").getResponse().getStatus())
                .isEqualTo(200);
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_preference where user_id = ?", Long.class, PREF_USER_B);
        assertThat(rowCount).as("本人偏好单行（upsert 不新增行）").isEqualTo(1L);
    }

    @Test
    void putPreferencesThemePreferenceOnlyKeepsMessagePushValue() throws Exception {
        // given: 主体 B 先立 messagePush=true，再仅 PUT themePreference（messagePush 域 null=不动）
        assertThat(putPrefs(PREF_USER_B, "{\"messagePush\": true}").getResponse().getStatus())
                .isEqualTo(200);

        // when
        JsonNode putBody = readBody(putPrefs(PREF_USER_B, """
                {"messagePush": null, "themePreference": %s}
                """.formatted(OTHER_BLOB.strip())));

        // then: messagePush 保持原值 true，themePreference 覆盖为新 blob；未设 messagePush 域缺省 false
        assertThat(putBody.path("code").asText()).isEqualTo("0");
        assertThat(putBody.path("data").path("messagePush").asBoolean())
                .as("仅 themePreference 域 PUT 不得触碰 messagePush（按域 upsert，确认记录②）").isTrue();
        assertThat(putBody.path("data").path("themePreference")).isEqualTo(JSON.readTree(OTHER_BLOB));

        JsonNode echoed = readBody(callAs(PREF_USER_B, get("/admin/v1/system/me/preferences")));
        assertThat(echoed.path("data").path("messagePush").asBoolean()).isTrue();
        assertThat(echoed.path("data").path("themePreference")).isEqualTo(JSON.readTree(OTHER_BLOB));
    }

    @Test
    void putPreferencesRejectsBothDomainsNull() throws Exception {
        // given: 主体 C 已有合法数据（fail-closed 基线）
        seedValidData(PREF_USER_C);

        // when: 两域皆 null（可空域至少一非 null，钉死事实）
        MvcResult nullBoth = putPrefs(PREF_USER_C,
                "{\"messagePush\": null, \"themePreference\": null}");
        MvcResult emptyBody = putPrefs(PREF_USER_C, "{}");

        // then: 400 校验错误码，且原数据不动
        assertRejected(nullBoth, "两域皆 null 应 400");
        assertRejected(emptyBody, "两域皆缺省应 400");
        assertDataUnchanged(PREF_USER_C);
    }

    // ---------- AC-3：themePreference 校验拒绝矩阵（fail-closed：400 校验错误码 + 原数据不动） ----------

    @Test
    void putPreferencesRejectsUnknownTopLevelKeyInThemePreference() throws Exception {
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"mode": "dark"}, "mysteryTopKey": true}
                """, "themePreference 未知顶层键应 400");
    }

    @Test
    void putPreferencesRejectsUnknownPreferenceKey() throws Exception {
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"mode": "dark", "notAPreferenceKey": true}}
                """, "preferences 未知偏好键应 400");
    }

    @Test
    void putPreferencesRejectsBadModeEnum() throws Exception {
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"mode": "sepia"}}
                """, "mode 坏枚举应 400");
    }

    @Test
    void putPreferencesAcceptsEmeraldAccentWhenNewPresetRegistered() throws Exception {
        // TL 通告（12 预设权威表回填）：新增六键之一 emerald 应在 accent 白名单内（正例回归锚点）
        JsonNode putBody = readBody(putPrefs(PREF_USER_A, """
                {"messagePush": null, "themePreference": {"schemaVersion": 3, "preferences": {"accent": "emerald"}}}
                """));

        assertThat(putBody.path("code").asText()).isEqualTo("0");
        assertThat(putBody.path("data").path("themePreference").path("preferences").path("accent").asText())
                .isEqualTo("emerald");

        JsonNode echoed = readBody(callAs(PREF_USER_A, get("/admin/v1/system/me/preferences")));
        assertThat(echoed.path("data").path("themePreference").path("preferences").path("accent").asText())
                .as("新增预设键 emerald 应存储并回显").isEqualTo("emerald");
    }

    @Test
    void putPreferencesRejectsBrandHueTealAsAccent() throws Exception {
        // teal 是扩展品牌色（仅图标/点缀，UI-SPEC §2.1）非 accent 预设——旧占位错误键应拒
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"accent": "teal"}}
                """, "accent 传入品牌色 teal（非预设键）应 400");
    }

    @Test
    void putPreferencesRejectsBadAccentEnum() throws Exception {
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"accent": "hotpink"}}
                """, "accent 坏枚举应 400");
    }

    @Test
    void putPreferencesRejectsNonHexCustomAccent() throws Exception {
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"customAccent": "#12345"}}
                """, "customAccent 非六位 hex 应 400");
    }

    @Test
    void putPreferencesRejectsNonIntegerSchemaVersion() throws Exception {
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 1.5, "preferences": {"mode": "dark"}}
                """, "schemaVersion 非整数应 400");
    }

    @Test
    void putPreferencesRejectsOverLimitSingleExtensionKey() throws Exception {
        // T3-3：单键值 20_001 字符 > 16KB（明显超限，不贴边界）
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"mode": "dark"}, "extensions": {"big": "%s"}}
                """.formatted("a".repeat(20_001)), "extensions 单键超 16KB 应 400");
    }

    @Test
    void putPreferencesRejectsOverTotalExtensionsSize() throws Exception {
        // T3-3：六键各 12_500 字符，总计 75_000 字符 > 64KB（单键均未超 16KB）
        String twelveAndHalfK = "a".repeat(12_500);
        assertMatrixCase(PREF_USER_C, """
                {"schemaVersion": 3, "preferences": {"mode": "dark"}, "extensions": {"e1": "%s", "e2": "%s", "e3": "%s", "e4": "%s", "e5": "%s", "e6": "%s"}}
                """.formatted(twelveAndHalfK, twelveAndHalfK, twelveAndHalfK,
                        twelveAndHalfK, twelveAndHalfK, twelveAndHalfK),
                "extensions 总量超 64KB 应 400");
    }

    @Test
    void putPreferencesAcceptsLegalExtensionsPassthrough() throws Exception {
        // given/when: 合法 extensions（object 结构合规、未超限）随 blob 提交
        JsonNode putBody = readBody(putPrefs(PREF_USER_A, """
                {"messagePush": null, "themePreference": {"schemaVersion": 3, "preferences": {"mode": "dark"}, "extensions": {"widget": {"label": "hi", "n": 7}}}}
                """));

        // then: 存储 + GET 原样回显（内容透传，确认记录③⑤）
        assertThat(putBody.path("code").asText()).isEqualTo("0");
        JsonNode extensions = putBody.path("data").path("themePreference").path("extensions");
        assertThat(extensions.path("widget").path("label").asText()).isEqualTo("hi");
        assertThat(extensions.path("widget").path("n").asInt()).isEqualTo(7);

        JsonNode echoed = readBody(callAs(PREF_USER_A, get("/admin/v1/system/me/preferences")));
        assertThat(echoed.path("data").path("themePreference").path("extensions"))
                .as("合法 extensions 应原样透传回显").isEqualTo(extensions);
    }

    // ---------- AC-4：未设行 GET 缺省（后端不内置主题默认值） ----------

    @Test
    void getMePreferencesReturnsFalseMessagePushAndNullThemePreferenceWhenNoRow() throws Exception {
        // given: 主体 D 全用例从未写过偏好行；when: GET
        JsonNode body = readBody(callAs(PREF_USER_D, get("/admin/v1/system/me/preferences")));

        // then: messagePush=false + themePreference=null（确认记录④）
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("messagePush").asBoolean()).isFalse();
        assertThat(body.path("data").path("themePreference").isNull())
                .as("后端不内置主题默认值，未设行 themePreference 应为 null").isTrue();
    }

    // ---------- 支撑 ----------

    /** PUT /admin/v1/system/me/preferences（JSON 体捷径）。 */
    private MvcResult putPrefs(long userId, String json) throws Exception {
        return mvc.perform(callAsBuilder(userId, put("/admin/v1/system/me/preferences"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andReturn();
    }

    /** 以指定主体执行请求。 */
    private MvcResult callAs(long userId, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(callAsBuilder(userId, request)).andReturn();
    }

    /** 指定主体请求构建（内部令牌头注入，REQ-082531 同款口径）。 */
    private MockHttpServletRequestBuilder callAsBuilder(long userId, MockHttpServletRequestBuilder request) {
        return request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(new AuthenticatedSubject(
                        String.valueOf(userId), "default", Channel.B, Set.of("super_admin"))));
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 经 API 种子建立主体 C 的合法两域基线（T3-4：不走 JDBC 直插，不猜 prefs 内部形态）。 */
    private void seedValidData(long userId) throws Exception {
        MvcResult seed = putPrefs(userId, """
                {"messagePush": true, "themePreference": %s}
                """.formatted(VALID_BLOB.strip()));
        assertThat(seed.getResponse().getStatus())
                .as("fail-closed 用例前置：合法两域 PUT 种子应成功").isEqualTo(200);
    }

    /** 校验拒绝矩阵公共断言：400 + success=false + 既有校验错误码段（T3-1 正则收紧）。 */
    private void assertRejected(MvcResult result, String asText) throws Exception {
        assertThat(result.getResponse().getStatus()).as(asText).isEqualTo(400);
        JsonNode body = readBody(result);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText())
                .as("应沿用既有校验错误码段（REQ-082531 终稿口径，不新增段）")
                .matches("SYS-01-\\d{4}");
    }

    /** 矩阵用例骨架：种子合法基线 → 非法 PUT → 400 信封 → GET 原数据不动。 */
    private void assertMatrixCase(long userId, String themePreferenceJson, String asText) throws Exception {
        seedValidData(userId);
        MvcResult result = putPrefs(userId, """
                {"messagePush": null, "themePreference": %s}
                """.formatted(themePreferenceJson.strip()));
        assertRejected(result, asText);
        assertDataUnchanged(userId);
    }

    /** fail-closed：被拒绝 PUT 后 GET 回显与种子基线整树相等。 */
    private void assertDataUnchanged(long userId) throws Exception {
        JsonNode echoed = readBody(callAs(userId, get("/admin/v1/system/me/preferences")));
        assertThat(echoed.path("code").asText()).isEqualTo("0");
        assertThat(echoed.path("data").path("messagePush").asBoolean())
                .as("非法 PUT 不得触碰 messagePush 域").isTrue();
        assertThat(echoed.path("data").path("themePreference"))
                .as("非法 PUT 不得覆盖 themePreference 原数据（fail-closed）")
                .isEqualTo(JSON.readTree(VALID_BLOB));
    }
}
