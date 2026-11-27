package com.acme.platform.system;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.system.api.SystemErrorCodes;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.application.UserUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * REQ-20261125-001738 用户名唯一域收敛（platform-system）服务域集成测试（Testcontainers PG，
 * 装配口径照 {@code SystemIntegrationTest}/{@code REQ20261122150802TenantLifecycleQuotaIntegrationTest}；
 * 无 Docker 自动跳过——判定口径：日志须见 Tests run: N, Skipped: 0 才算验证）。
 *
 * <p>覆盖契约验收标准：</p>
 * <ul>
 *   <li>AC-1：租户 A/B 分别创建同名用户均成功；同租户重名创建 → USERNAME_DUPLICATED。</li>
 *   <li>AC-3：internal 查档按 (tenantCode, username) 域内命中；未知租户/域内无此用户统一失败（同码，不泄露差异）。</li>
 *   <li>AC-5：开通初始化合成管理员名跨租户同名不阻断；与域内既有用户冲突 → USERNAME_DUPLICATED。</li>
 *   <li>AC-6：V34 唯一约束 = (tenant_id, username)（deleted 不入键，软删占名语义沿用 V1）；存量零冲突；语句重放幂等。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义就地标注，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 V34 文件名按 {@code classpath*:db/migration/system/V34__*.sql} 通配解析（V35 测试先例口径）。</li>
 *   <li>AMB-2 软删占名语义：测试域 schema.sql 的 V1 形状为 {@code UNIQUE (username)}（deleted 不入键），
 *       契约 AC-6 钉死「约束=唯一(tenant_id, username)」——故断言约束列恰为 tenant_id+username，
 *       软删行仍占名（若实现核实 V1 另含 deleted 维度，按裁决调整此断言）。</li>
 *   <li>AMB-3 internal 查档响应形状沿用 AuthProfileView（data.tenantId / data.username），契约钉死「返回形状不变」。</li>
 *   <li>AMB-4 AC-5 域内冲突通过「init 前预置目标租户同名用户」构造（tenant_id 即 tenant_code，无外键强制——
 *       REQ20261114120104TenantIsolationIntegrationTest 先例）。</li>
 * </ul>
 */
class REQ202611251738UsernameTenantUniqueIntegrationTest extends AbstractSystemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 夹具前缀：teardown 按此前缀物理清理（每用例独立名字避免互相污染）。 */
    private static final String FIXTURE_PREFIX = "req1738";

    private static final String TENANT_A = FIXTURE_PREFIX + "-tenant-a";
    private static final String TENANT_B = FIXTURE_PREFIX + "-tenant-b";
    private static final String TENANT_C = FIXTURE_PREFIX + "-tenant-c";

    private static final String LIST_URL = "/admin/v1/system/tenants";

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserUseCase userUseCase;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
    }

    @AfterEach
    void cleanUp() {
        String prefix = FIXTURE_PREFIX + "%";
        jdbcTemplate.update("delete from sys_user_role where tenant_id like ? or user_id in "
                + "(select id from sys_user where username like ?)", prefix, prefix);
        jdbcTemplate.update("delete from sys_role_permission where role_id in "
                + "(select id from sys_role where tenant_id like ?)", prefix);
        jdbcTemplate.update("delete from sys_role_menu where tenant_id like ?", prefix);
        jdbcTemplate.update("delete from sys_user where username like ? or tenant_id like ?", prefix, prefix);
        jdbcTemplate.update("delete from sys_role where tenant_id like ?", prefix);
        jdbcTemplate.update("delete from sys_tenant where tenant_code like ?", prefix);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-1：跨租户同名均可创建 / 同租户重名拒绝 ----------

    @Test
    void should_createSameUsernameInBothTenants_when_tenantsDiffer() {
        bindTenant(TENANT_A);
        userUseCase.create(new AdminUserCreate(FIXTURE_PREFIX + "-u1", "租户A用户", null, null, List.of()));
        bindTenant(TENANT_B);
        userUseCase.create(new AdminUserCreate(FIXTURE_PREFIX + "-u1", "租户B用户", null, null, List.of()));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ?", Long.class, FIXTURE_PREFIX + "-u1"))
                .as("跨租户同名应各落一行（全局唯一已收敛为域内唯一）").isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList(
                "select tenant_id from sys_user where username = ?", String.class, FIXTURE_PREFIX + "-u1"))
                .as("两行分别归属 A/B 域").containsExactlyInAnyOrder(TENANT_A, TENANT_B);
    }

    @Test
    void should_rejectDuplicateUsername_when_sameTenant() {
        bindTenant(TENANT_A);
        userUseCase.create(new AdminUserCreate(FIXTURE_PREFIX + "-dup", "同名用户", null, null, List.of()));

        assertThatThrownBy(() -> userUseCase.create(
                new AdminUserCreate(FIXTURE_PREFIX + "-dup", "同名用户再建", null, null, List.of())))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .as("同租户重名应 USERNAME_DUPLICATED").isEqualTo(SystemErrorCodes.USERNAME_DUPLICATED));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ? and tenant_id = ?",
                Long.class, FIXTURE_PREFIX + "-dup", TENANT_A))
                .as("被拒的重名不应落库").isEqualTo(1L);
    }

    // ---------- AC-3：internal 查档域内命中 + 失败统一口径 ----------

    @Test
    void should_returnDomainOwnProfile_when_authProfileQueriedByTenantAndUsername() throws Exception {
        bindTenant(TENANT_A);
        userUseCase.create(new AdminUserCreate(FIXTURE_PREFIX + "-shared", "A域共享名", null, null, List.of()));
        bindTenant(TENANT_B);
        userUseCase.create(new AdminUserCreate(FIXTURE_PREFIX + "-shared", "B域共享名", null, null, List.of()));

        JsonNode profileA = callAsSuperAdmin(get("/internal/v1/auth-profiles")
                .param("tenantCode", TENANT_A).param("username", FIXTURE_PREFIX + "-shared"));
        assertThat(profileA.path("code").asText()).as("A 域查档应成功：%s", profileA).isEqualTo("0");
        assertThat(profileA.path("data").path("tenantId").asText())
                .as("A 域应命中 A 域画像（AMB-3）").isEqualTo(TENANT_A);

        JsonNode profileB = callAsSuperAdmin(get("/internal/v1/auth-profiles")
                .param("tenantCode", TENANT_B).param("username", FIXTURE_PREFIX + "-shared"));
        assertThat(profileB.path("code").asText()).as("B 域查档应成功：%s", profileB).isEqualTo("0");
        assertThat(profileB.path("data").path("tenantId").asText())
                .as("B 域应命中 B 域画像（AMB-3）").isEqualTo(TENANT_B);
    }

    @Test
    void should_failWithSameCode_when_tenantUnknownOrUsernameAbsentInTenant() throws Exception {
        bindTenant(TENANT_A);
        userUseCase.create(new AdminUserCreate(FIXTURE_PREFIX + "-shared", "A域共享名", null, null, List.of()));

        JsonNode unknownTenant = callAsSuperAdmin(get("/internal/v1/auth-profiles")
                .param("tenantCode", FIXTURE_PREFIX + "-no-such-tenant")
                .param("username", FIXTURE_PREFIX + "-shared"));
        JsonNode absentInTenant = callAsSuperAdmin(get("/internal/v1/auth-profiles")
                .param("tenantCode", TENANT_A).param("username", FIXTURE_PREFIX + "-no-such-user"));

        assertThat(unknownTenant.path("success").asBoolean()).as("未知租户应失败").isFalse();
        assertThat(absentInTenant.path("success").asBoolean()).as("域内无此用户应失败").isFalse();
        assertThat(unknownTenant.path("code").asText()).as("未知租户 code≠0").isNotEqualTo("0");
        assertThat(absentInTenant.path("code").asText()).as("域内无此用户 code≠0").isNotEqualTo("0");
        assertThat(unknownTenant.path("code").asText())
                .as("未知租户与域内无此用户同码（统一口径、不泄露差异）")
                .isEqualTo(absentInTenant.path("code").asText());
    }

    // ---------- AC-5：开通管理员名域内冲突 / 跨租户同名放行 ----------

    @Test
    void should_allowCrossTenantSameAdminName_when_initTenant() throws Exception {
        assertThat(initTenant(TENANT_A, FIXTURE_PREFIX + "_shared_admin").path("code").asText())
                .as("A 域开通应成功").isEqualTo("0");
        JsonNode initB = initTenant(TENANT_B, FIXTURE_PREFIX + "_shared_admin");
        assertThat(initB.path("code").asText()).as("B 域以同名管理员开通应成功（跨租户不再阻断）").isEqualTo("0");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ?", Long.class,
                FIXTURE_PREFIX + "_shared_admin")).as("两个租户各落一行同名管理员").isEqualTo(2L);
    }

    @Test
    void should_rejectInitWithUsernameDuplicated_when_adminNameConflictsInTargetTenant() throws Exception {
        // 预置目标域内同名用户（AMB-4：tenant_id 即 tenant_code，无外键强制）
        bindTenant(TENANT_C);
        userUseCase.create(new AdminUserCreate(
                FIXTURE_PREFIX + "_c_admin", "目标域既有同名用户", null, null, List.of()));

        JsonNode result = initTenant(TENANT_C, FIXTURE_PREFIX + "_c_admin");

        assertThat(result.path("success").asBoolean()).as("域内同名应阻断开通：%s", result).isFalse();
        assertThat(result.path("code").asText())
                .as("合成管理员名与域内既有用户冲突 → USERNAME_DUPLICATED")
                .isEqualTo(SystemErrorCodes.USERNAME_DUPLICATED);
    }

    // ---------- AC-6：V34 约束形状 + 软删占名 + 存量零冲突 + 幂等 ----------

    @Test
    void should_enforceUniqueTenantUsername_when_v34Applied() {
        assertV34ResourcesPresent();

        List<List<String>> uniqueConstraints = uniqueConstraintColumnsOfSysUser();
        assertThat(uniqueConstraints).as("sys_user 应存在唯一约束").isNotEmpty();
        assertThat(uniqueConstraints).as("V34 后唯一约束列恰为 (tenant_id, username)，deleted 不入键（AMB-2）")
                .anySatisfy(cols -> assertThat(cols).containsExactlyInAnyOrder("tenant_id", "username"));
        assertThat(uniqueConstraints).as("不得残留仅 username 的全局唯一约束（旧 uk_sys_user_username）")
                .noneSatisfy(cols -> assertThat(cols).containsExactly("username"));
    }

    @Test
    void should_keepSoftDeletedRowOccupyingName_when_v34Applied() {
        assertV34ResourcesPresent();
        long baseId = System.nanoTime() & 0x7FFFFFFFL | 0x700000000L;

        // A 域已存在（含软删）同名行 → 同域再插（无论 deleted 取值）应落入唯一约束冲突
        insertUserDirect(baseId, TENANT_A, FIXTURE_PREFIX + "-occupy", true);
        assertThatThrownBy(() -> insertUserDirect(baseId + 1, TENANT_A, FIXTURE_PREFIX + "-occupy", false))
                .as("软删行仍占名（deleted 不在唯一键内，沿用 V1 语义）")
                .isInstanceOf(DataIntegrityViolationException.class);

        // 跨租户同名仍可落库
        assertThatCode(() -> insertUserDirect(baseId + 2, TENANT_B, FIXTURE_PREFIX + "-occupy", false))
                .doesNotThrowAnyException();

        // 存量零冲突：不存在 (tenant_id, username) 重复组（全局唯一蕴含域内唯一）
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from (
                    select tenant_id, username from sys_user group by tenant_id, username having count(*) > 1
                ) duplicates
                """, Long.class)).as("迁移后既有数据零冲突").isZero();
    }

    @Test
    void should_keepConstraintAndDataStable_when_v34StatementsReplayed() throws IOException {
        assertV34ResourcesPresent();
        long baseId = System.nanoTime() & 0x7FFFFFFFL | 0x800000000L;
        insertUserDirect(baseId, TENANT_A, FIXTURE_PREFIX + "-replay", false);

        List<List<String>> before = uniqueConstraintColumnsOfSysUser();
        long userCountBefore = jdbcTemplate.queryForObject("select count(*) from sys_user", Long.class);
        long replayUsernameCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ?", Long.class, FIXTURE_PREFIX + "-replay");

        replayV34Statements();
        replayV34Statements();

        assertThat(uniqueConstraintColumnsOfSysUser()).as("重放两次后约束形状零变化（幂等守卫）").isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_user", Long.class))
                .as("重放不得改写/新增用户数据").isEqualTo(userCountBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ?", Long.class, FIXTURE_PREFIX + "-replay"))
                .as("夹具行不因重放丢失").isEqualTo(replayUsernameCountBefore);
    }

    // ---------- 支撑 ----------

    private void assertV34ResourcesPresent() {
        try {
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/system/V34__*.sql");
            assertThat(resources)
                    .as("V34 迁移脚本缺失（backend 未落盘或命名不符 V34__*.sql）——预期红锚点")
                    .isNotEmpty();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 重放 V34 全量语句（契约钉死幂等守卫）。V34 采用 V33 同款 {@code DO $$ ... $$;} 目录守卫块
     * （backend 口径），块内分号不可裸切——按 dollar-quote 块整体提取后逐条执行，避免 PG IT 误红。
     */
    private void replayV34Statements() throws IOException {
        var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V34__*.sql");
        assertThat(resources).as("V34 迁移脚本缺失").isNotEmpty();
        for (var resource : resources) {
            String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String chunk : splitSqlStatements(script)) {
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

    /** 按分号切分，但 {@code $$...$$;} 块整体保留为一条语句。 */
    private static List<String> splitSqlStatements(String script) {
        List<String> statements = new ArrayList<>();
        int cursor = 0;
        while (cursor < script.length()) {
            int dollarStart = script.indexOf("$$", cursor);
            int semicolon = script.indexOf(';', cursor);
            if (dollarStart >= 0 && (semicolon < 0 || dollarStart < semicolon)) {
                int dollarEnd = script.indexOf("$$;", dollarStart + 2);
                if (dollarEnd < 0) {
                    throw new IllegalStateException("DO $$ 块未闭合：V34 脚本格式异常");
                }
                statements.add(script.substring(cursor, dollarEnd + 3));
                cursor = dollarEnd + 3;
            } else if (semicolon >= 0) {
                statements.add(script.substring(cursor, semicolon + 1));
                cursor = semicolon + 1;
            } else {
                String tail = script.substring(cursor).strip();
                if (!tail.isEmpty()) {
                    statements.add(tail);
                }
                break;
            }
        }
        return statements;
    }

    /** sys_user 上每个唯一约束的列集合（约束名→列名的分组）。 */
    private List<List<String>> uniqueConstraintColumnsOfSysUser() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select tc.constraint_name as constraint_name, kcu.column_name as column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu on kcu.constraint_name = tc.constraint_name
                where tc.table_schema = 'public' and tc.table_name = 'sys_user'
                  and tc.constraint_type = 'UNIQUE'
                order by tc.constraint_name, kcu.ordinal_position
                """);
        Map<String, List<String>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(String.valueOf(row.get("constraint_name")), key -> new ArrayList<>())
                    .add(String.valueOf(row.get("column_name")));
        }
        return new ArrayList<>(grouped.values());
    }

    private void insertUserDirect(long id, String tenantCode, String username, boolean deleted) {
        jdbcTemplate.update("""
                        insert into sys_user (id, tenant_id, username, nickname, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, 'ENABLED', false, now(), ?, now(), ?, 0, ?)
                        """,
                id, tenantCode, username, username, FIXTURE_PREFIX + "-it", FIXTURE_PREFIX + "-it", deleted);
    }

    private void bindTenant(String tenantCode) {
        TenantContext.bind(new TenantContext(tenantCode));
        UserContext.bind(new UserContext("1", Set.of("super_admin")));
    }

    /** init 开通（契约确认：adminUsername 合成名；templateCode 必填走兜底，语义不变）。 */
    private JsonNode initTenant(String tenantCode, String adminUsername) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantCode", tenantCode);
        body.put("tenantName", tenantCode + " 开通夹具租户");
        body.putNull("remark");
        body.put("adminUsername", adminUsername);
        body.put("adminNickname", tenantCode + " 管理员");
        body.put("templateCode", FIXTURE_PREFIX + "-unmatched-template");
        body.putNull("parentId");
        return callAsSuperAdmin(post(LIST_URL + "/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private JsonNode callAsSuperAdmin(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("X-Acme-Channel", "B")
                        .header("X-Acme-Token", tokenIssuer.issue(
                                new AuthenticatedSubject("1", "default", Channel.B, Set.of("super_admin"))))
                        .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                                new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
