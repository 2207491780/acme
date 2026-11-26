package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.BeforeEach;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261125-230807（AC-15 + V36 迁移幂等/回填正确性）sys_permission.service 迁移集成测试
 * （Testcontainers PG，装配口径照抄 {@code SystemIntegrationTest}/
 * {@code REQ20261119051119ButtonMenuMigrationTest}；无 Docker 自动跳过——判定口径：
 * Tests run: N, Skipped: 0）。
 *
 * <p>契约（API 节「sys_permission.service（V36 迁移）」+ 钉死事实④ + AC-15）：
 * + {@code service varchar(32) NULL}；回填规则——{@code split_part(http_path,'/',4)} 为主（path 第 4 段）、
 * {@code permission_code} 首段兜底、{@code content→contents} 归一；V36 回填后全部种子行可解析（AC-15）；
 * AdminPermissionView + service（Create/Update DTO 不加——service 只由迁移维护）。</p>
 *
 * <p>口径说明（契约歧义/并行实现假设就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-6 V36 文件名按 {@code classpath*:db/migration/system/V36__*.sql} 通配解析
 *       （SystemIntegrationTest V26/V27/V32 先例口径）。</li>
 *   <li>AMB-7 幂等重放=迁移语句<b>全量重放</b>（非仅 INSERT——契约钉死 V35/V36 带幂等守卫，V24/V32
 *       「仅 INSERT」口径不适用本 REQ 的 DDL 面）；假设迁移语句不含分号字面量与 dollar-quote 块
 *       （同 SystemIntegrationTest 种子重放假设），违者红灯交 TL 裁决。</li>
 *   <li>AMB-8 回填规则判别器：种子行 path 第 4 段与 permission_code 首段普遍同值（无法区分主/兜底规则），
 *       主规则优先级由夹具判别行钉死（path 4 段 ≠ code 首段的行回填值必取 path 段）。</li>
 *   <li>AMB-9 夹具回填经「重放 V36 语句」触发：若实现回填语句带 {@code WHERE service IS NULL} 守卫，
 *       夹具行（新插 service=NULL）仍被覆盖；若为无守卫全量 UPDATE 同样覆盖——两种实现形态下断言一致。</li>
 * </ul>
 *
 * <p>预期红（实现未落地，钉死事实⑨）：service 列缺失/迁移文件缺失/回填未执行 → 断言失败=实现缺口锚点；
 * 装配失败/夹具 SQL 语法错才属用例缺陷需自修。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=Req230807Delta3SystemTestHmacSecret48Bfix000pad0"
})
class REQ20261125230807PermissionServiceV36MigrationIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PERMISSIONS_URL = "/admin/v1/system/permissions";

    private static final String FIXTURE_PREFIX = "req230807";

    /** 夹具 ID 区间（teardown 按区间清理）。 */
    private static final long ID_BASE = 92308080000L;
    private static final long ID_TOP = 92308089999L;

    /** 夹具 INSERT 列面照抄 V1 sys_permission 列集（REQ-214653 tester AMB-B5 数据面申报口径）。 */
    private static final String INSERT_PERMISSION = """
            insert into sys_permission (id, permission_code, permission_name, permission_type, http_method,
                                        http_path, status, created_at, created_by, updated_at, updated_by, version, deleted)
            values (?, ?, ?, 'API', ?, ?, 'ENABLED', now(), 'req230807-it', now(), 'req230807-it', 0, false)
            """;

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
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_permission where id between ? and ?", ID_BASE, ID_TOP);
        com.acme.common.context.TenantContext.clear();
        com.acme.common.context.UserContext.clear();
    }

    // ---------- V36 迁移就绪：列形状按契约钉死 ----------

    @Test
    void should_add_service_column_when_v36_applied() {
        assertV36ResourcesPresent();
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                select udt_name, character_maximum_length, is_nullable
                from information_schema.columns
                where table_schema = 'public' and table_name = 'sys_permission' and column_name = 'service'
                """);
        assertThat(String.valueOf(column.get("udt_name")))
                .as("service 应为 varchar（契约钉死 varchar(32)）").isEqualTo("varchar");
        Object length = column.get("character_maximum_length");
        assertThat(length == null ? null : ((Number) length).intValue())
                .as("service 长度=32（契约钉死 varchar(32)）").isEqualTo(32);
        assertThat(column.get("is_nullable")).as("service 可空（契约钉死 NULL）").isEqualTo("YES");
    }

    // ---------- V36 回填正确性：种子行三规则不变量（AC-15：全部种子行可解析） ----------

    @Test
    void should_backfill_all_seed_rows_by_contract_rules_when_v36_applied() {
        assertV36ResourcesPresent();

        // AC-15：V36 回填后全部种子行可解析（无 NULL service；夹具行不在库——teardown 已清理）
        assertThat(queryCount("select count(*) from sys_permission where service is null"))
                .as("AC-15：全部种子行 service 可解析（无 NULL）").isZero();

        // 主规则：http_path 第 4 段（content→contents 归一）——钉死事实④
        assertThat(queryCount("""
                select count(*) from sys_permission
                where service is not null
                  and http_path is not null and http_path <> ''
                  and split_part(http_path, '/', 4) <> ''
                  and service <> case when split_part(http_path, '/', 4) = 'content'
                                      then 'contents' else split_part(http_path, '/', 4) end
                """))
                .as("path 可解析行：service 必须=split_part(http_path,'/',4)（content→contents 归一）").isZero();

        // 兜底规则：permission_code 首段（content→contents 归一）——钉死事实④
        assertThat(queryCount("""
                select count(*) from sys_permission
                where service is not null
                  and (http_path is null or http_path = '' or split_part(http_path, '/', 4) = '')
                  and service <> case when split_part(permission_code, ':', 1) = 'content'
                                      then 'contents' else split_part(permission_code, ':', 1) end
                """))
                .as("path 缺失行：service 必须=permission_code 首段（content→contents 归一）").isZero();

        // 归一终态：任何行不得残留 'content' 服务段
        assertThat(queryCount("select count(*) from sys_permission where service = 'content'"))
                .as("content→contents 归一后不应存在 service='content'").isZero();
    }

    // ---------- V36 幂等：全量语句重放两次，状态零变化 ----------

    @Test
    void should_keep_permission_state_stable_when_v36_statements_replayed() throws IOException {
        assertV36ResourcesPresent();

        long rowCountBefore = queryCount("select count(*) from sys_permission");
        Map<String, String> serviceByCodeBefore = serviceMap();
        long nullServiceBefore = queryCount("select count(*) from sys_permission where service is null");

        replayV36Statements();
        replayV36Statements();

        assertThat(queryCount("select count(*) from sys_permission")).isEqualTo(rowCountBefore);
        assertThat(queryCount("select count(*) from sys_permission where service is null"))
                .isEqualTo(nullServiceBefore);
        assertThat(serviceMap()).as("重放两次后 service 回填值零变化（幂等守卫）")
                .containsExactlyInAnyOrderEntriesOf(serviceByCodeBefore);
    }

    // ---------- 回填规则判别器（夹具经重放触发回填，AMB-8/AMB-9） ----------

    @Test
    void should_backfill_fixture_rows_by_primary_path_rule_when_v36_replayed() throws IOException {
        assertV36ResourcesPresent();
        long fallbackId = insertPermission(ID_BASE + 1, "it-" + FIXTURE_PREFIX + ":probe", null, null);
        long contentsId = insertPermission(ID_BASE + 2, "content:" + FIXTURE_PREFIX + ":probe",
                "GET", "/admin/v1/contents/" + FIXTURE_PREFIX);
        // 判别行：path 第 4 段（alpha）≠ code 首段（beta）→ 主规则优先（AMB-8）
        long discriminatorId = insertPermission(ID_BASE + 3, "beta-" + FIXTURE_PREFIX + ":probe",
                "GET", "/admin/v1/alpha-" + FIXTURE_PREFIX + "/x");

        replayV36Statements();

        assertThat(serviceOf(fallbackId))
                .as("兜底规则：path 缺失行 service=permission_code 首段")
                .isEqualTo("it-" + FIXTURE_PREFIX);
        assertThat(serviceOf(contentsId))
                .as("主规则+归一：path 第 4 段 contents → service=contents")
                .isEqualTo("contents");
        assertThat(serviceOf(discriminatorId))
                .as("主规则优先：path 第 4 段 ≠ code 首段时取 path 段（split_part 主、首段兜底）")
                .isEqualTo("alpha-" + FIXTURE_PREFIX);
    }

    // ---------- AC-15 wire 面：权限点列表披露 service（值=既有 http_method 之外的迁移列） ----------

    @Test
    void should_expose_service_field_when_permission_page_returned() throws Exception {
        assertV36ResourcesPresent();
        MvcResult result = performAsSuperAdmin(get(PERMISSIONS_URL)
                .queryParam("page", "1").queryParam("size", "200"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("success").asBoolean()).as("权限点分页应成功：%s", body).isTrue();
        assertThat(body.path("code").asText()).isEqualTo("0");

        JsonNode records = body.path("data").path("records");
        assertThat(records.isArray()).as("分页 records 应为数组").isTrue();
        assertThat(records.size()).as("分页应有记录（种子 87 行）").isPositive();

        Map<String, String> dbService = serviceMap();
        for (JsonNode record : records) {
            String code = record.path("permissionCode").asText(null);
            assertThat(code).as("记录应携带 permissionCode").isNotNull();
            assertThat(record.path("service").isMissingNode())
                    .as("AC-15：wire 应披露 service 列（缺失=实现披露缺口，%s）", code).isFalse();
            assertThat(record.path("service").isNull())
                    .as("AC-15：wire service 非空（%s）", code).isFalse();
            assertThat(record.path("service").asText()).isEqualTo(dbService.get(code));
            // AC-15：请求方式值来自既有 http_method（列透传回归锚）
            String dbHttpMethod = jdbcTemplate.queryForObject(
                    "select coalesce(http_method, '') from sys_permission where permission_code = ?",
                    String.class, code);
            assertThat(record.path("httpMethod").isMissingNode())
                    .as("AC-15：wire 应披露既有 httpMethod（%s）", code).isFalse();
            assertThat(record.path("httpMethod").asText("")).isEqualTo(dbHttpMethod);
        }
    }

    // ---------- 支撑 ----------

    private void assertV36ResourcesPresent() {
        try {
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/system/V36__*.sql");
            assertThat(resources)
                    .as("V36 迁移脚本缺失（backend 未落盘或命名不符 V36__*.sql）——预期红锚点")
                    .isNotEmpty();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void replayV36Statements() throws IOException {
        var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V36__*.sql");
        assertThat(resources).as("V36 迁移脚本缺失").isNotEmpty();
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

    private long insertPermission(long id, String code, String httpMethod, String httpPath) {
        jdbcTemplate.update(INSERT_PERMISSION, id, code, FIXTURE_PREFIX + " 判别夹具", httpMethod, httpPath);
        return id;
    }

    private Map<String, String> serviceMap() {
        Map<String, String> map = new HashMap<>();
        jdbcTemplate.query(
                "select permission_code, coalesce(service, '') as service from sys_permission",
                rs -> {
                    map.put(rs.getString(1), rs.getString(2));
                });
        return map;
    }

    private String serviceOf(long id) {
        return jdbcTemplate.queryForObject("select service from sys_permission where id = ?", String.class, id);
    }

    private long queryCount(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? -1L : value;
    }

    private MvcResult performAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(new com.acme.framework.core.support
                                .AuthenticatedSubject("1", "default", com.acme.common.context.Channel.B,
                                Set.of("super_admin"))))
                        .requestAttr(com.acme.framework.core.support.ClientContextDraft.ATTRIBUTE_NAME,
                                new com.acme.framework.core.support.ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }
}
