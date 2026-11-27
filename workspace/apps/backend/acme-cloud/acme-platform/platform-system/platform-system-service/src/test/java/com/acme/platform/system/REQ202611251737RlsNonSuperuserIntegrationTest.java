package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.test.EmbeddedPostgresSupport;
import com.acme.framework.tenant.support.TenantBypassScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261125-001737 AC-3 / AC-4 / AC-5 铁证：真正以 V33 迁移创建的非超管应用角色
 * 建立 JDBC 连接（TL 裁决：禁止 superuser 登录 + SET ROLE 冒充），生产传播层仍作用于 Spring 托管
 * DataSource，故 TenantContext 绑定/旁路会照常写会话变量。
 *
 * <p>装配：主 DataSource 使用 {@code acme_rls_app/acme_rls_app_pwd} 登录；Flyway 另用容器超管账号
 * 完成 V33（角色由迁移创建，先有库后有应用连接）。跨租户核对/夹具清理另建独立 superuser
 * JdbcTemplate——仅用于取「未被 RLS 裁剪的真相」，绝不作为隔离铁证。</p>
 *
 * <p>无 Docker 自动跳过不等于验证；判定口径为日志 {@code Tests run: N, Skipped: 0}。</p>
 */
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req-1737-nonsuper-it-secret-0123456789abcdef"
})
class REQ202611251737RlsNonSuperuserIntegrationTest {

    private static final String APP_ROLE = "acme_rls_app";
    private static final String APP_PASSWORD = "acme_rls_app_pwd";
    private static final String RLS_DB = "acme_rls_nonsuper";
    private static final String TENANT_A = "req1737-rls-t1";
    private static final String TENANT_B = "req1737-rls-t2";
    private static final String USER_A = "req1737-ns-own";
    private static final String USER_B = "req1737-ns-other";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport pg = EmbeddedPostgresSupport.shared();
        // 应用连接：迁移创建的非超管角色（铁证连接的 login 身份）
        registry.add("spring.datasource.url", () -> pg.jdbcUrl(RLS_DB, "public"));
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> "-1");
        // 迁移连接：嵌入式 PG 超管（V33 在其上创建 schema/角色）
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

    @Test
    void should_connectAsNonSuperuserApplicationRole_when_usingMigratedCredentials() {
        String loginRole = jdbcTemplate.queryForObject("select current_user", String.class);
        Boolean superuser = jdbcTemplate.queryForObject(
                "select usesuper from pg_user where usename = current_user", Boolean.class);

        assertThat(loginRole).as("铁证必须以迁移创建的非超管应用角色 login").isEqualTo(APP_ROLE);
        assertThat(superuser).as("login 角色不得为 superuser（否则 RLS 无效）").isFalse();
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
}
