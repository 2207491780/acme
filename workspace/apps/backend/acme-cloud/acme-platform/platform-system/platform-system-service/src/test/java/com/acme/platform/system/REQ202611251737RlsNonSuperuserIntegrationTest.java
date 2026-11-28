package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.framework.tenant.support.TenantBypassScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261127-234335 AC-2 / AC-3 / AC-4 / AC-5 / AC-8 铁证：真正以 V33 迁移创建的非超管应用角色
 * 建立 JDBC 连接（TL 裁决：禁止 superuser 登录 + SET ROLE 冒充），生产传播层仍作用于 Spring 托管
 * DataSource，故 TenantContext 绑定/旁路会照常写会话变量。
 *
 * <p>装配（V37 收口后，REQ-20261127-234335 决策⑥）：V37 将 {@code acme_rls_app} 收口为集群级
 * NOLOGIN + 口令清空（生产形态），铁证用例的登录口令改由配置注入——属性
 * {@code acme.rls.it.role-password}（环境变量 {@code ACME_RLS_IT_ROLE_PASSWORD} 可覆盖），缺失即
 * fail-fast；夹具 {@link V37RoleReopenFixture} 经定制 {@link FlywayMigrationStrategy} 在 Flyway 迁移
 * 完成后、应用连接池首次使用前，用嵌入式 PG 超管参数化重开 LOGIN（NOLOGIN 是集群级属性，迁移 SQL 内
 * 不做环境分支、不写死口令）。主 DataSource 以 {@code acme_rls_app} + 注入口令登录；Flyway 另用嵌入式
 * PG 超管完成 V33→V37 迁移。跨租户核对/夹具清理另建独立 superuser JdbcTemplate——仅用于取
 * 「未被 RLS 裁剪的真相」，绝不作为隔离铁证。</p>
 *
 * <p>无 Docker 自动跳过不等于验证；判定口径为日志 {@code Tests run: N, Skipped: 0}。</p>
 */
@SpringBootTest(classes = {PlatformSystemApplication.class,
        REQ202611251737RlsNonSuperuserIntegrationTest.V37RoleReopenFixture.class}, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-1737-nonsuper-it-secret-0123456789abcdef"
})
class REQ202611251737RlsNonSuperuserIntegrationTest {

    private static final String APP_ROLE = "acme_rls_app";
    /** V37 新增的可信旁路角色（策略旁路分支的第二判据，AC-2 探针必须不是其成员）。 */
    private static final String BYPASS_ROLE = "acme_rls_bypass";
    private static final String RLS_DB = "acme_rls_nonsuper";
    private static final String TENANT_A = "req1737-rls-t1";
    private static final String TENANT_B = "req1737-rls-t2";
    private static final String USER_A = "req1737-ns-own";
    private static final String USER_B = "req1737-ns-other";

    /**
     * V37 收口夹具（仅本测试上下文生效）：应用角色口令的 fail-fast 校验与配置注入、
     * Flyway 迁移完成后重开 {@code acme_rls_app} LOGIN。Java 源零口令字面量。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class V37RoleReopenFixture {

        /** V37 收口后应用角色口令的配置注入键（relaxed binding；环境变量可覆盖）。 */
        private static final String ROLE_PASSWORD_PROPERTY = "acme.rls.it.role-password";
        private static final String ROLE_PASSWORD_ENV = "ACME_RLS_IT_ROLE_PASSWORD";

        private final Environment environment;

        V37RoleReopenFixture(Environment environment) {
            this.environment = environment;
        }

        /**
         * fail-fast + 口令注入（静态 @Bean 的 BPP 在 DataSource 绑定前执行）：配置键缺失/空白即中断
         * 上下文启动并给出配置指引；否则把注入口令写为 {@code spring.datasource.password} 最高优先
         * 属性源，供应用连接池与迁移后重开的 LOGIN 使用同一来源。
         */
        @Bean
        static BeanFactoryPostProcessor rlsItRolePasswordBinder(Environment environment) {
            return beanFactory -> ((ConfigurableEnvironment) environment).getPropertySources()
                    .addFirst(new MapPropertySource("rls-it-configured-role-password",
                            Map.of("spring.datasource.password", requireConfiguredPassword(environment))));
        }

        /**
         * Flyway 迁移完成后（V33→V37 全部应用、角色已 NOLOGIN）、应用连接池首次建立连接前，
         * 以嵌入式 PG 超管重开 {@code acme_rls_app} LOGIN。口令与本上下文
         * {@code spring.datasource.password} 同源同值（同一配置键）。
         *
         * <p>PG 的 {@code ALTER ROLE ... PASSWORD} 子句仅接受字符串常量，拒绝绑定参数（PG 17 实测
         * PSQLException「语法错误，位置在 $1 或其附近」）——故经一次性 {@code pg_temp} 临时 plpgsql
         * 函数中转：口令值以 JDBC 绑定参数传入，由 {@code quote_literal} 转义后动态执行，连接关闭
         * 即随 temp schema 消失（零残留）；口令值全程不出现在任何 SQL 文本中。</p>
         */
        @Bean
        FlywayMigrationStrategy reopenAppRoleLoginAfterMigration() {
            return flyway -> {
                flyway.migrate();
                String password = requireConfiguredPassword(environment);
                try (Connection admin = EmbeddedPostgresSupport.shared().openAdminConnection(RLS_DB)) {
                    try (Statement st = admin.createStatement()) {
                        st.execute("""
                                CREATE FUNCTION pg_temp.acme_rls_it_reopen_role(p_password text) RETURNS void
                                LANGUAGE plpgsql AS $fn$
                                BEGIN
                                    EXECUTE 'ALTER ROLE acme_rls_app LOGIN PASSWORD '
                                            || quote_literal(p_password);
                                END
                                $fn$
                                """);
                    }
                    try (PreparedStatement ps = admin.prepareStatement(
                            "SELECT pg_temp.acme_rls_it_reopen_role(?)")) {
                        ps.setString(1, password);
                        ps.execute();
                    }
                } catch (SQLException ex) {
                    throw new IllegalStateException(
                            "V37 NOLOGIN 收口后重开 acme_rls_app LOGIN 失败（IT 夹具时序）", ex);
                }
            };
        }

        /** 配置键必须已解析且非空白；缺失即 fail-fast 并给出键名与环境变量名指引。 */
        private static String requireConfiguredPassword(Environment environment) {
            String password = environment.getProperty(ROLE_PASSWORD_PROPERTY);
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("RLS IT 夹具缺少应用角色联调口令：属性 '"
                        + ROLE_PASSWORD_PROPERTY + "' 未解析或为空白。请在 "
                        + "platform-system-service/src/test/resources/application.yml 配置该键，"
                        + "或经环境变量 '" + ROLE_PASSWORD_ENV + "' 注入"
                        + "（禁止回填明文常量、禁止沿用 V33 历史口令）。");
            }
            return password;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        // 应用连接：迁移创建的非超管角色（铁证连接的 login 身份）；口令由 V37RoleReopenFixture 经
        // 配置键注入（BPP 阶段写入最高优先属性源），本方法不出现任何口令
        registry.add("spring.datasource.url", () -> pg.jdbcUrl(RLS_DB, "public"));
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> "-1");
        // 迁移连接：嵌入式 PG 超管（V33/V37 在其上创建/收口角色）
        registry.add("spring.flyway.user", pg::username);
        registry.add("spring.flyway.password", pg::password);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
    }

    /** 生产传播层作用的非超管连接（Spring DataSource bean）。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate transactions;
    /** 仅用于夹具播种、跨租户真相核对与清理；不作为隔离铁证。 */
    private JdbcTemplate adminJdbc;

    @BeforeEach
    void setUp() {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        // 传播层按钉死事实只在 autoCommit=false 的 JDBC 事务内写 SET LOCAL。
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        adminJdbc = new JdbcTemplate(new DriverManagerDataSource(
                pg.jdbcUrl(RLS_DB, "public"), pg.username(), pg.password()));
    }

    @AfterEach
    void cleanUp() {
        adminJdbc.update("delete from sys_user where username like 'req1737-ns-%'");
        TenantContext.clear();
    }

    /**
     * NOLOGIN 是集群级角色属性：夹具重开的 LOGIN 会残留到后续测试类（如 Policy 侧 AC-1 断言
     * rolcanlogin=false + NOLOGIN 拒连）。本类结束后恢复 V37 生产形态（NOLOGIN + 口令清空），
     * 与探针角色「用后即删」同一夹具卫生口径。
     */
    @AfterAll
    static void restoreClusterRoleState() throws SQLException {
        try (Connection admin = EmbeddedPostgresSupport.shared().openAdminConnection(RLS_DB);
             Statement st = admin.createStatement()) {
            st.execute("ALTER ROLE acme_rls_app NOLOGIN");
            st.execute("ALTER ROLE acme_rls_app PASSWORD NULL");
        }
    }

    @Test
    void should_connectAsNonSuperuserApplicationRole_when_usingConfiguredCredentials() {
        String loginRole = jdbcTemplate.queryForObject("select current_user", String.class);
        Boolean superuser = jdbcTemplate.queryForObject(
                "select usesuper from pg_user where usename = current_user", Boolean.class);

        assertThat(loginRole).as("铁证必须以迁移创建的非超管应用角色 login").isEqualTo(APP_ROLE);
        assertThat(superuser).as("login 角色不得为 superuser（否则 RLS 无效）").isFalse();
    }

    // ---------- AC-2 铁证：旁路分支单独失效（自设 GUC 不再越权） ----------

    /**
     * 非旁路成员的普通登录角色仅自设 {@code app.tenant_bypass='on'}（不设 app.tenant_id）后，
     * SELECT 空集、UPDATE/DELETE 命中 0 行——V37 旁路分支追加 pg_has_role 成员判据后，
     * 单独的 GUC 不再获得任何额外行（fail-closed，V-01 修复铁证）。
     *
     * <p>探针角色运行时随机生成（非超管、非 acme_rls_bypass 成员、用后即删）。表权限最小化：
     * 仅 public schema USAGE + 试点表 sys_user 的 SELECT/UPDATE/DELETE——UPDATE/DELETE 授权是
     * 「命中 0 行」铁证的前提：PG 先做 ACL 检查再做 RLS 过滤，无写权限的 DML 只会在
     * permission denied 处失败，验证不到 RLS 层的旁路分支失效（委派单字面「仅授 SELECT」与
     * 「UPDATE/DELETE 命中 0 行」在 PG 语义下互斥，按 AC-2 核心 then 口径取舍并上报 leader）。</p>
     */
    @Test
    void should_hitZeroRows_when_plainRoleSelfSetsBypassGuc() throws SQLException {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        seedUser(USER_A, TENANT_A);
        seedUser(USER_B, TENANT_B);

        String probeRole = "req1737_guc_probe_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String probePassword = "req1737-probe-" + UUID.randomUUID();
        boolean probeCreated = false;
        Connection probe = null;
        try {
            try (Connection admin = pg.openAdminConnection(RLS_DB)) {
                // 探针角色运行时生成（口令 UUID 派生，非入库凭证）；CREATE ROLE 的 PASSWORD 子句
                // 同样仅接受字符串常量（见 reopenAppRoleLoginAfterMigration 注释），故经 pg_temp
                // 临时函数中转：角色名与口令均走 JDBC 绑定参数（quote_ident / quote_literal 转义）
                try (Statement st = admin.createStatement()) {
                    st.execute("""
                            CREATE FUNCTION pg_temp.acme_rls_it_setup_probe(p_rolname name, p_password text)
                            RETURNS void LANGUAGE plpgsql AS $fn$
                            BEGIN
                                EXECUTE 'CREATE ROLE ' || quote_ident(p_rolname)
                                        || ' LOGIN PASSWORD ' || quote_literal(p_password);
                                EXECUTE 'GRANT USAGE ON SCHEMA public TO '
                                        || quote_ident(p_rolname);
                                EXECUTE 'GRANT SELECT, UPDATE, DELETE ON public.sys_user TO '
                                        || quote_ident(p_rolname);
                            END
                            $fn$
                            """);
                }
                try (PreparedStatement ps = admin.prepareStatement(
                        "SELECT pg_temp.acme_rls_it_setup_probe(?, ?)")) {
                    ps.setString(1, probeRole);
                    ps.setString(2, probePassword);
                    ps.execute();
                }
                probeCreated = true;
            }

            Boolean superuser = adminJdbc.queryForObject(
                    "select rolsuper from pg_roles where rolname = ?", Boolean.class, probeRole);
            Boolean bypassMember = adminJdbc.queryForObject("""
                            select exists(select 1 from pg_auth_members m
                            join pg_roles bypass on bypass.oid = m.roleid
                            join pg_roles member_role on member_role.oid = m.member
                            where bypass.rolname = ? and member_role.rolname = ?)
                            """, Boolean.class, BYPASS_ROLE, probeRole);
            assertThat(superuser).as("探针必须为非超管").isFalse();
            assertThat(bypassMember).as("探针不得是 acme_rls_bypass 成员（铁证前提）").isFalse();

            // 探针直连（不经 Spring 托管 DataSource）：开事务仅自设旁路 GUC（SET LOCAL 语义），
            // 不设 app.tenant_id
            probe = DriverManager.getConnection(pg.jdbcUrl(RLS_DB, "public"), probeRole, probePassword);
            probe.setAutoCommit(false);
            try (Statement st = probe.createStatement()) {
                st.execute("SELECT set_config('app.tenant_bypass', 'on', true)");
            }

            List<String> visible = new ArrayList<>();
            try (Statement st = probe.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select username from sys_user where username like 'req1737-ns-%'")) {
                while (rs.next()) {
                    visible.add(rs.getString(1));
                }
            }
            assertThat(visible).as("自设旁路 GUC 的非成员主体：SELECT 必须空集").isEmpty();

            try (Statement st = probe.createStatement()) {
                int updated = st.executeUpdate(
                        "update sys_user set nickname = 'req1737-guc-probe' where username like 'req1737-ns-%'");
                assertThat(updated).as("自设旁路 GUC 的非成员主体：UPDATE 必须命中 0 行").isZero();

                int deleted = st.executeUpdate(
                        "delete from sys_user where username like 'req1737-ns-%'");
                assertThat(deleted).as("自设旁路 GUC 的非成员主体：DELETE 必须命中 0 行").isZero();
            }
            probe.rollback();
        } finally {
            closeQuietly(probe);
            if (probeCreated) {
                // teardown：先 REVOKE 后 DROP ROLE（探针连接已关闭，无会话占用；角色名随机、用后即删）
                try (Connection admin = pg.openAdminConnection(RLS_DB)) {
                    try (Statement st = admin.createStatement()) {
                        st.execute("""
                                CREATE FUNCTION pg_temp.acme_rls_it_teardown_probe(p_rolname name)
                                RETURNS void LANGUAGE plpgsql AS $fn$
                                BEGIN
                                    EXECUTE 'REVOKE SELECT, UPDATE, DELETE ON public.sys_user FROM '
                                            || quote_ident(p_rolname);
                                    EXECUTE 'REVOKE USAGE ON SCHEMA public FROM '
                                            || quote_ident(p_rolname);
                                    EXECUTE 'DROP ROLE ' || quote_ident(p_rolname);
                                END
                                $fn$
                                """);
                    }
                    try (PreparedStatement ps = admin.prepareStatement(
                            "SELECT pg_temp.acme_rls_it_teardown_probe(?)")) {
                        ps.setString(1, probeRole);
                        ps.execute();
                    }
                }
            }
        }

        // 超管视角核对真相（非铁证）：旁路分支单独失效不得命中/修改任何夹具行
        assertThat(adminJdbc.queryForObject(
                "select count(*) from sys_user where username like 'req1737-ns-%'", Long.class))
                .as("fail-closed 不得破坏夹具行").isEqualTo(2L);
    }

    // ---------- AC-3 铁证 ----------

    @Test
    void should_isolateDirectSqlToBoundTenant_when_nonSuperuserApplicationRoleUsed() {
        seedUser(USER_A, TENANT_A);
        seedUser(USER_B, TENANT_B);

        TenantContext.bind(new TenantContext(TENANT_A));
        try {
            transactions.executeWithoutResult(status -> {
                Boolean superuser = jdbcTemplate.queryForObject(
                        "select usesuper from pg_user where usename = current_user", Boolean.class);
                assertThat(superuser).as("铁证事务连接的 effective user 必须是非超管").isFalse();
                assertThat(jdbcTemplate.queryForObject(
                        "select current_setting('app.tenant_id', true)", String.class))
                        .as("生产传播层必须在非超管连接上写入绑定租户").isEqualTo(TENANT_A);

                List<String> visible = jdbcTemplate.queryForList(
                        "select username from sys_user where username like 'req1737-ns-%' order by username",
                        String.class);
                assertThat(visible).as("SELECT：绑定 t-1 只可见本租户行").containsExactly(USER_A);

                int updated = jdbcTemplate.update(
                        "update sys_user set nickname = 'req1737-hacked' where username like 'req1737-ns-%'");
                assertThat(updated).as("UPDATE：只能改到本租户行").isEqualTo(1);

                int deleted = jdbcTemplate.update(
                        "delete from sys_user where username like 'req1737-ns-%'");
                assertThat(deleted).as("DELETE：只能删到本租户行").isEqualTo(1);
            });
        } finally {
            TenantContext.clear();
        }

        // 超管视角核对真相（非铁证）：跨租户行完好，本租户行已删
        assertThat(nicknameOf(USER_B)).as("跨租户行不得被改写").isEqualTo(USER_B);
        assertThat(adminJdbc.queryForObject(
                "select count(*) from sys_user where username = ?", Long.class, USER_A))
                .as("本租户行应已被删除").isZero();
        assertThat(adminJdbc.queryForObject(
                "select count(*) from sys_user where username = ?", Long.class, USER_B))
                .as("跨租户行不得被删除").isEqualTo(1L);
    }

    // ---------- AC-4 旁路 ----------

    @Test
    void should_seeAndModifyAllTenants_when_bypassActiveOnNonSuperuserConnection() {
        seedUser(USER_A, TENANT_A);
        seedUser(USER_B, TENANT_B);

        TenantContext.bind(new TenantContext(TENANT_A));
        try (TenantBypassScope ignored = TenantBypassScope.open("req1737-ac4-nonsuper")) {
            transactions.executeWithoutResult(status -> {
                assertThat(jdbcTemplate.queryForObject(
                        "select current_setting('app.tenant_bypass', true)", String.class))
                        .as("旁路激活必须写 app.tenant_bypass=on").isEqualTo("on");

                assertThat(jdbcTemplate.queryForList(
                        "select username from sys_user where username like 'req1737-ns-%' order by username",
                        String.class)).as("旁路 SELECT 应见全租户行")
                        .containsExactlyInAnyOrder(USER_A, USER_B);

                assertThat(jdbcTemplate.update(
                        "update sys_user set nickname = 'req1737-bypass' where username like 'req1737-ns-%'"))
                        .as("旁路 UPDATE 应命中全租户行").isEqualTo(2);
                assertThat(jdbcTemplate.update(
                        "delete from sys_user where username like 'req1737-ns-%'"))
                        .as("旁路 DELETE 应命中全租户行").isEqualTo(2);
            });
        } finally {
            TenantContext.clear();
        }
        assertThat(adminJdbc.queryForObject(
                "select count(*) from sys_user where username like 'req1737-ns-%'", Long.class))
                .as("旁路删除后夹具行应清空").isZero();
    }

    // ---------- AC-5 fail-closed ----------

    @Test
    void should_returnEmptySet_when_noContextAndNoBypassOnNonSuperuserConnection() {
        seedUser(USER_A, TENANT_A);
        seedUser(USER_B, TENANT_B);

        TenantContext.clear();
        transactions.executeWithoutResult(status -> {
            assertThat(jdbcTemplate.queryForList(
                    "select username from sys_user where username like 'req1737-ns-%'", String.class))
                    .as("无上下文无旁路：SELECT 必须空集").isEmpty();
            assertThat(jdbcTemplate.update(
                    "update sys_user set nickname = 'req1737-nobody' where username like 'req1737-ns-%'"))
                    .as("无上下文 UPDATE 不得命中任何行").isZero();
            assertThat(jdbcTemplate.update(
                    "delete from sys_user where username like 'req1737-ns-%'"))
                    .as("无上下文 DELETE 不得命中任何行").isZero();
        });

        assertThat(adminJdbc.queryForObject(
                "select count(*) from sys_user where username like 'req1737-ns-%'", Long.class))
                .as("fail-closed 不得破坏夹具行").isEqualTo(2L);
    }

    // AC-6（API 面）由 REQ202611251737PropagationIntegrationTest 覆盖；平台目录表未启用 RLS
    // 由 REQ202611251737RlsPolicyIntegrationTest 的 pg_class 断言覆盖。非超管应用角色仅被授予
    // 试点表 DML 权限，不对目录表做越权 SELECT（不自行 GRANT，避免掩盖迁移授权事实）。

    // ---------- 支撑 ----------

    private void seedUser(String username, String tenantId) {
        adminJdbc.update("""
                        insert into sys_user (id, tenant_id, username, nickname, status, built_in,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                        values (?, ?, ?, ?, 'ENABLED', false, now(), 'req1737-ns-it', now(), 'req1737-ns-it', 0, false)
                        """,
                Math.abs(System.identityHashCode(username)) & 0x7FFFFFFFL | 0x900000000L,
                tenantId, username, username);
    }

    private String nicknameOf(String username) {
        List<String> values = adminJdbc.queryForList(
                "select nickname from sys_user where username = ?", String.class, username);
        return values.isEmpty() ? null : values.get(0);
    }

    /** teardown 期间关闭失败不掩盖主断言（连接随后由 finally 的 REVOKE/DROP 前提保证无会话占用）。 */
    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 探针连接关闭失败极罕见；DROP ROLE 会因会话占用报错，不做静默降级
            }
        }
    }
}
