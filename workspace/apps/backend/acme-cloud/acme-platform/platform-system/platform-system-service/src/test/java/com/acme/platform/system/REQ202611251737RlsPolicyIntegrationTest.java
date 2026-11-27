package com.acme.platform.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-001737 AC-2：V33 迁移真库验证（Testcontainers PG，装配口径照
 * {@code REQ20261114120104TenantIsolationIntegrationTest}；无 Docker 自动跳过——判定口径为日志
 * {@code Tests run: N, Skipped: 0}，跳过不豁免验证）。
 *
 * <p>覆盖：试点表（system 域全部带租户列表）全部 ENABLE+FORCE RLS；USING/WITH CHECK 策略口径
 * =确认记录②；平台目录表不启用 RLS（确认记录③）；迁移创建的非超管应用角色就绪；脚本重复执行
 * 不报错、不重复建策略。</p>
 *
 * <p>隔离铁证（AC-3~AC-6）在 {@code REQ202611251737RlsNonSuperuserIntegrationTest} 中以真正非超管
 * 应用角色登录完成；本类使用容器超管仅检查目录元数据，不作为隔离铁证。</p>
 */
class REQ202611251737RlsPolicyIntegrationTest extends AbstractSystemIntegrationTest {

    /** backend 工人确认的迁移应用角色（TL 转达；密码不入契约）。 */
    private static final String APP_ROLE = "acme_rls_app";

    private static final Set<String> NAMED_PILOT_TABLES = Set.of(
            "sys_user", "sys_role", "sys_dept", "sys_position", "sys_dict_type",
            "sys_dict_item", "sys_config", "sys_notice", "sys_message");

    private static final List<String> CATALOG_TABLES = List.of(
            "sys_tenant", "sys_permission", "sys_menu", "sys_tenant_package", "sys_tenant_init_template");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_enableAndForceRlsWithContractPolicy_when_v33Applied() throws Exception {
        Resource[] v33 = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V33__*.sql");
        assertThat(v33).as("V33 迁移脚本缺失（backend 未落盘或命名不符）").isNotEmpty();

        assertV33PolicyState();
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

    @Test
    void should_replayV33WithoutErrorOrDuplicatePolicies_when_migrationExecutedTwice() throws Exception {
        Resource[] v33 = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/system/V33__*.sql");
        assertThat(v33).as("V33 迁移脚本缺失").isNotEmpty();

        Map<String, Long> before = policyCounts();
        for (Resource resource : v33) {
            for (String statement : splitSqlScript(new String(resource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8))) {
                jdbcTemplate.execute(statement);
            }
        }
        Map<String, Long> after = policyCounts();

        assertThat(after).as("重复执行 V33 不得重复建策略/改变试点表集合").isEqualTo(before);
        assertV33PolicyState();
    }

    // ---------- 支撑 ----------

    private void assertV33PolicyState() {
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
            assertThat(policies).as("%s 策略口径必须=确认记录②（USING 与 WITH CHECK 同式）", table)
                    .anySatisfy(policy -> {
                        assertPolicyExpression(policy.get("qual"), table + ".USING");
                        assertPolicyExpression(policy.get("with_check"), table + ".WITH CHECK");
                    });
        }

        for (String table : CATALOG_TABLES) {
            Boolean enabled = jdbcTemplate.queryForObject("""
                    select relrowsecurity from pg_class where oid = to_regclass(?)::oid
                    """, Boolean.class, "public." + table);
            assertThat(enabled).as("平台目录表 %s 不得启用 RLS（确认记录③）", table).isFalse();
        }
    }

    /**
     * 试点表口径=契约确认⑤「以 V33 迁移枚举为准」：契约点名的 9 张表 + V33 实际建立租户策略的表。
     * 不能简单取「所有带 tenant_id 的表」——sys_tenant_quota 等租户生命周期管理表不在本 REQ 试点范围。
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

    private static void assertPolicyExpression(Object raw, String label) {
        assertThat(raw).as("%s 不得为空（USING/WITH CHECK 均需定义）", label).isNotNull();
        String expression = normalizePolicy(String.valueOf(raw));
        assertThat(expression).as("%s 口径必须=确认记录②", label)
                .contains("tenant_id = current_setting('app.tenant_id', true)")
                .contains("current_setting('app.tenant_bypass', true) = 'on'")
                .contains(" OR ");
    }

    /** PG 会把表达式存成 ::text 强转与冗余括号，归一化后再按契约表达式断言（语义不放宽）。 */
    private static String normalizePolicy(String raw) {
        return raw.replaceAll("\\s+", " ")
                .replace("::text", "")
                .replace("::character varying", "")
                .replace("::bpchar", "")
                .replace("(tenant_id)", "tenant_id")
                .replace("('on')", "'on'")
                .trim();
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
