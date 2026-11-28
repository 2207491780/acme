package com.acme.platform.system;

import com.acme.common.test.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261125-001737 AC-2 基础上按 REQ-20261127-234335（B1 P0 安全：RLS 租户隔离收口 V-01，V37）
 * 改造：RLS 策略真库验证（零 Docker 嵌入式 PG17 装配口径照 {@code AbstractSystemIntegrationTest}；
 * 判定口径为日志 {@code Tests run: N, Failures: 0, Errors: 0, Skipped: 0}，无 Docker 自动跳过不豁免验证）。
 *
 * <p>REQ-20261127-234335 改造点：</p>
 * <ul>
 *   <li>策略口径断言升级为确认③联合判定式：tenant_id 自设 GUC 等式 + 旁路 GUC 等式 +
 *       {@code pg_has_role(current_user, 'acme_rls_bypass', 'member')} 可信角色成员判据
 *       （USING 与 WITH CHECK 同式）。第 3 要素是 V33 旧口径（旁路分支仅凭会话可自设 GUC，
 *       评审 Tier0 Veto 的 V-01）的判别项，缺它即旧口径回潮。</li>
 *   <li>AC-1 角色收口铁证：在刚重放过 V37 的同一方法内断言 {@code acme_rls_app} NOLOGIN 并以
 *       运行时生成的哑口令直连被拒——NOLOGIN 是集群级角色属性，可能被并行测试类夹具
 *       （NonSuperuser 配置注入口令重开 LOGIN）翻回，脱离重放上下文断言必 flake。</li>
 *   <li>AC-7 授权矩阵铁证：{@code acme_rls_bypass} NOLOGIN/非超管；成员关系用 pg_auth_members
 *       目录直查（不依赖 pg_has_role 函数语义）；13 张试点表全部 ENABLE+FORCE RLS 且策略=新联合判定式。</li>
 *   <li>幂等重放改为 V33→V37 顺序重放：V33 重放会把策略重建回旧口径（旁路仅凭 GUC），只放 V33
 *       后断言新口径必红——顺序重放后新口径仍成立才是防回潮铁证，不是 flake。</li>
 * </ul>
 *
 * <p>隔离铁证（REQ-20261127-234335 AC-2~AC-5/AC-8）在 {@code REQ202611251737RlsNonSuperuserIntegrationTest}
 * 中以真正非超管应用角色连接完成；本类使用嵌入式 PG 超管仅检查目录元数据与迁移幂等性，
 * 不作为隔离铁证。</p>
 */
class REQ202611251737RlsPolicyIntegrationTest extends AbstractSystemIntegrationTest {

    /** backend 工人确认的迁移应用角色（TL 转达；密码不入契约，V37 起无条件 NOLOGIN）。 */
    private static final String APP_ROLE = "acme_rls_app";

    /** REQ-20261127-234335 确认③：旁路可信角色（NOLOGIN、非超管），策略旁路判据绑定其成员资格。 */
    private static final String BYPASS_ROLE = "acme_rls_bypass";

    private static final Set<String> NAMED_PILOT_TABLES = Set.of(
            "sys_user", "sys_role", "sys_dept", "sys_position", "sys_dict_type",
            "sys_dict_item", "sys_config", "sys_notice", "sys_message");

    private static final List<String> CATALOG_TABLES = List.of(
            "sys_tenant", "sys_permission", "sys_menu", "sys_tenant_package", "sys_tenant_init_template");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 历史命名沿用（REQ-20261125-001737 门禁映射）：Flyway 应用链最终态=V37，本用例校验应用后
     * 的 hardening 库态（V37 不改试点集合与目录表口径，V33 时代断言经 {@code assertPolicyExpression}
     * 升级后对新旧口径有判别力）。
     */
    @Test
    void should_enableAndForceRlsWithContractPolicy_when_v33Applied() throws Exception {
        Resource[] v33 = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V33__*.sql");
        assertThat(v33).as("V33 迁移脚本缺失（backend 未落盘或命名不符）").isNotEmpty();

        assertHardenedPolicyState();
    }

    @Test
    void should_createNonSuperuserApplicationRole_when_v33Applied() {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from pg_roles where rolname = ?)", Boolean.class, APP_ROLE);
        assertThat(exists).as("V33 必须创建非超管应用角色 %s（铁证前提）", APP_ROLE).isTrue();

        Boolean superuser = jdbcTemplate.queryForObject(
                "select rolsuper from pg_roles where rolname = ?", Boolean.class, APP_ROLE);
        assertThat(superuser).as("应用角色必须是非超管，否则 RLS 铁证无效").isFalse();

        Boolean canRead = jdbcTemplate.queryForObject("""
                select has_table_privilege(?, 'public.sys_user', 'SELECT')
                """, Boolean.class, APP_ROLE);
        Boolean canUpdate = jdbcTemplate.queryForObject("""
                select has_table_privilege(?, 'public.sys_user', 'UPDATE')
                """, Boolean.class, APP_ROLE);
        Boolean canDelete = jdbcTemplate.queryForObject("""
                select has_table_privilege(?, 'public.sys_user', 'DELETE')
                """, Boolean.class, APP_ROLE);
        assertThat(canRead).as("应用角色必须有试点表 SELECT 权限").isTrue();
        assertThat(canUpdate).as("应用角色必须有试点表 UPDATE 权限").isTrue();
        assertThat(canDelete).as("应用角色必须有试点表 DELETE 权限").isTrue();
    }

    /**
     * REQ-20261127-234335 AC-1 角色收口铁证：先在同一方法内重放 V37 重新无条件施加
     * NOLOGIN + PASSWORD NULL（集群级角色属性可能被并行测试类夹具翻回 LOGIN），随后断言
     * {@code pg_roles.rolcanlogin=false}，并以运行时生成的哑口令尝试 JDBC 直连。
     * NOLOGIN 检查先于口令校验（SQLSTATE 28000），哑口令即可触发 {@code is not permitted to log in}；
     * 禁止把历史明文写进测试。
     */
    @Test
    void should_rejectLegacyPasswordLogin_when_v37NologinApplied() throws Exception {
        Resource[] v37 = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V37__*.sql");
        assertThat(v37).as("V37 迁移脚本缺失（backend 未落盘或命名不符）").isNotEmpty();
        replayMigrations(v37);

        Boolean canLogin = jdbcTemplate.queryForObject(
                "select rolcanlogin from pg_roles where rolname = ?", Boolean.class, APP_ROLE);
        assertThat(canLogin).as("V37 后 %s 必须 NOLOGIN（rolcanlogin=false，集群级角色属性）", APP_ROLE)
                .isFalse();

        String dummyPassword = "it-" + UUID.randomUUID();
        // 断言经 assertThatThrownBy 走 Throwable 通道：SQLException 自身实现 Iterable<Throwable>，
        // 裸 assertThat(SQLException) 会撞 assertThat(Iterable) 与 assertThat(T) 的重载歧义。
        // 拒绝证据链用 locale 无关形态：嵌入式集群 initdb locale 非英文时服务端消息会本地化
        // （英文文案 is not permitted to log in 在该集群形态必红），SQLSTATE=28000 是 NOLOGIN
        // 拒绝的专属错误码（先于口令校验；角色存在性已由前一条 rolcanlogin 查询保证，同码的
        // role-does-not-exist 路径被排除），角色名在任意 locale 下原样内嵌于消息。
        assertThatThrownBy(() -> DriverManager.getConnection(
                        EmbeddedPostgresSupport.shared().jdbcUrlWithSchema("public"), APP_ROLE, dummyPassword))
                .as("%s 已 NOLOGIN，口令直连必须被拒绝", APP_ROLE)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(APP_ROLE)
                .matches(e -> "28000".equals(((SQLException) e).getSQLState()),
                        "SQLSTATE=28000（invalid_authorization_specification）");
    }

    /**
     * REQ-20261127-234335 AC-6 幂等重放铁证：必须按 V33→V37 顺序重放全部语句——V33 重放会把
     * 策略重建回旧口径（旁路仅凭 GUC 旁路），只放 V33 后断言新口径必红，这不是 flake 是防回潮
     * 铁证。重放后无报错、策略数量与试点表集合不变，且策略口径=新联合判定式。
     * 基座连接=嵌入式 PG 超管，可执行全部迁移语句。
     */
    @Test
    void should_replayV33ThenV37WithoutErrorOrDuplicatePolicies_when_migrationsReplayed() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] v33 = resolver.getResources("classpath*:db/migration/system/V33__*.sql");
        Resource[] v37 = resolver.getResources("classpath*:db/migration/system/V37__*.sql");
        assertThat(v33).as("V33 迁移脚本缺失").isNotEmpty();
        assertThat(v37).as("V37 迁移脚本缺失（backend 未落盘或命名不符）").isNotEmpty();

        Map<String, Long> before = policyCounts();
        replayMigrations(v33, v37);
        Map<String, Long> after = policyCounts();

        assertThat(after).as("V33→V37 顺序重放不得重复建策略/改变试点表集合").isEqualTo(before);
        assertHardenedPolicyState();
    }

    /**
     * REQ-20261127-234335 AC-7 授权矩阵铁证：{@code acme_rls_bypass} 存在且 NOLOGIN/非超管；
     * {@code acme_rls_app} 为其成员（pg_auth_members 目录直查，不依赖 pg_has_role 函数语义）；
     * 13 张试点表全部 ENABLE+FORCE RLS 且策略口径=新联合判定式。
     * 不对 {@code acme} 角色断言成员关系——嵌入式集群无该角色，迁移对其跳过是正确路径（契约钉死事实 4）。
     */
    @Test
    void should_bindBypassToTrustedRole_when_v37Applied() {
        Boolean bypassExists = jdbcTemplate.queryForObject(
                "select exists(select 1 from pg_roles where rolname = ?)", Boolean.class, BYPASS_ROLE);
        assertThat(bypassExists).as("V37 必须创建旁路可信角色 %s", BYPASS_ROLE).isTrue();

        Boolean bypassSuperuser = jdbcTemplate.queryForObject(
                "select rolsuper from pg_roles where rolname = ?", Boolean.class, BYPASS_ROLE);
        Boolean bypassCanLogin = jdbcTemplate.queryForObject(
                "select rolcanlogin from pg_roles where rolname = ?", Boolean.class, BYPASS_ROLE);
        assertThat(bypassSuperuser)
                .as("%s 必须非超管（旁路可信主体不得是超管，否则联合判定名存实亡）", BYPASS_ROLE).isFalse();
        assertThat(bypassCanLogin)
                .as("%s 必须 NOLOGIN（旁路可信主体不可登录，仅经成员资格生效）", BYPASS_ROLE).isFalse();

        Boolean membership = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from pg_auth_members am
                    join pg_roles m on m.oid = am.member
                    join pg_roles r on r.oid = am.roleid
                    where m.rolname = ? and r.rolname = ?
                )""", Boolean.class, APP_ROLE, BYPASS_ROLE);
        assertThat(membership)
                .as("%s 必须经授权矩阵成为 %s 成员（pg_auth_members 目录直查）", APP_ROLE, BYPASS_ROLE)
                .isTrue();

        assertPilotTablesHardened();
    }

    // ---------- 支撑 ----------

    /** 试点表硬断言：全部 ENABLE+FORCE RLS 且策略口径=确认③联合判定式（USING 与 WITH CHECK 同式）。 */
    private void assertPilotTablesHardened() {
        List<String> pilotTables = pilotTables();
        assertThat(pilotTables).as("system 域带租户列的表都应纳入试点").containsAll(NAMED_PILOT_TABLES);

        for (String table : pilotTables) {
            Map<String, Object> flags = jdbcTemplate.queryForMap("""
                    select relrowsecurity as rls, relforcerowsecurity as force
                    from pg_class where oid = to_regclass(?)::oid
                    """, "public." + table);
            assertThat(flags.get("rls")).as("%s 必须 ENABLE ROW LEVEL SECURITY", table).isEqualTo(true);
            assertThat(flags.get("force")).as("%s 必须 FORCE ROW LEVEL SECURITY（owner 也拦）", table)
                    .isEqualTo(true);

            List<Map<String, Object>> policies = jdbcTemplate.queryForList("""
                    select qual, with_check from pg_policies
                    where schemaname = 'public' and tablename = ?
                    """, table);
            assertThat(policies).as("%s 至少一条 RLS 策略", table).isNotEmpty();
            assertThat(policies).as("%s 策略口径必须=确认③联合判定式（USING 与 WITH CHECK 同式）", table)
                    .anySatisfy(policy -> {
                        assertPolicyExpression(policy.get("qual"), table + ".USING");
                        assertPolicyExpression(policy.get("with_check"), table + ".WITH CHECK");
                    });
        }
    }

    /** 迁移应用后（V33…V37 链最终态）的库态断言：试点表硬断言 + 平台目录表不启用 RLS。 */
    private void assertHardenedPolicyState() {
        assertPilotTablesHardened();

        for (String table : CATALOG_TABLES) {
            Boolean enabled = jdbcTemplate.queryForObject("""
                    select relrowsecurity from pg_class where oid = to_regclass(?)::oid
                    """, Boolean.class, "public." + table);
            assertThat(enabled).as("平台目录表 %s 不得启用 RLS", table).isFalse();
        }
    }

    /**
     * 试点表口径：契约点名 9 张表 + 实际建立租户策略的表（REQ-20261127-234335 V37 不改试点集合）。
     * 不能简单取「所有带 tenant_id 的表」——sys_tenant_quota 等租户生命周期管理表不在试点范围。
     */
    private List<String> pilotTables() {
        java.util.Set<String> tables = new java.util.TreeSet<>(NAMED_PILOT_TABLES);
        tables.addAll(jdbcTemplate.queryForList("""
                select distinct tablename from pg_policies
                where schemaname = 'public'
                  and (qual like '%app.tenant_id%' or with_check like '%app.tenant_id%')
                """, String.class));
        return new ArrayList<>(tables);
    }

    private Map<String, Long> policyCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select tablename, count(*) as n from pg_policies
                where schemaname = 'public' group by tablename order by tablename
                """)) {
            counts.put(String.valueOf(row.get("tablename")), ((Number) row.get("n")).longValue());
        }
        return counts;
    }

    /**
     * REQ-20261127-234335 确认③：策略口径=「自设 GUC + 可信角色成员」联合判定式（USING 与
     * WITH CHECK 同式），pg_policies 存储形态经归一化后按语义要素 contains 断言：
     * <ol>
     *   <li>tenant_id 等式：{@code tenant_id = current_setting('app.tenant_id', true)}</li>
     *   <li>旁路 GUC 等式：{@code current_setting('app.tenant_bypass', true) = 'on'}</li>
     *   <li>可信角色成员判据：{@code pg_has_role(current_user, 'acme_rls_bypass', 'member')}</li>
     * </ol>
     * 第 3 要素是 V33 旧口径的判别项——缺它即旧口径回潮；前两要素单独存在不足以证明收口。
     */
    private static void assertPolicyExpression(Object raw, String label) {
        assertThat(raw).as("%s 不得为空（USING/WITH CHECK 均需定义）", label).isNotNull();
        String expression = normalizePolicy(String.valueOf(raw));
        assertThat(expression).as("%s 口径必须=REQ-20261127-234335 确认③联合判定式", label)
                .contains("tenant_id = current_setting('app.tenant_id', true)")
                .contains("current_setting('app.tenant_bypass', true) = 'on'")
                .contains("pg_has_role(current_user, 'acme_rls_bypass', 'member')")
                .contains(" or ");
    }

    /**
     * PG 会把表达式存成类型强转（含 pg_has_role 参数被存为 ::name）、冗余括号与大小写不定
     * （current_user 可能存为 CURRENT_USER），归一化后按契约表达式断言（语义不放宽）：
     * 统一小写不影响判别——契约模板中的标识符与字符串字面量均为小写，大小写仅来自关键词存储形态。
     */
    private static String normalizePolicy(String raw) {
        return raw.replaceAll("\\s+", " ")
                .toLowerCase()
                .replace("::text", "")
                .replace("::character varying", "")
                .replace("::bpchar", "")
                .replace("::name", "")
                .replace("(tenant_id)", "tenant_id")
                .replace("('on')", "'on'")
                .trim();
    }

    /** 按组顺序重放迁移脚本：整组执行完前一组再执行下一组（V33→V37 顺序语义由此保证）。 */
    private void replayMigrations(Resource[]... scriptGroups) throws Exception {
        for (Resource[] group : scriptGroups) {
            for (Resource resource : group) {
                for (String statement : splitSqlScript(new String(resource.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8))) {
                    jdbcTemplate.execute(statement);
                }
            }
        }
    }

    /** 拆分迁移脚本：跳过 -- 注释，尊重单引号与 $$（或 $tag$）美元引号，避免切开过程体内的分号。 */
    static List<String> splitSqlScript(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        String dollarTag = null;
        for (int i = 0; i < script.length();) {
            char c = script.charAt(i);
            if (dollarTag != null) {
                if (script.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length();
                    dollarTag = null;
                } else {
                    current.append(c);
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
                i++;
                continue;
            }
            if (c == '$' && !inSingleQuote && !inDoubleQuote) {
                int end = i + 1;
                while (end < script.length() && (Character.isLetterOrDigit(script.charAt(end))
                        || script.charAt(end) == '_')) {
                    end++;
                }
                if (end < script.length() && script.charAt(end) == '$') {
                    dollarTag = script.substring(i, end + 1);
                    current.append(dollarTag);
                    i = end + 1;
                    continue;
                }
            }
            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                addStatement(statements, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }
}
