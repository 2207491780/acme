package com.acme.platform.infra;

import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import com.acme.framework.tenant.support.CrossTenantAuditLogger;
import com.acme.framework.tenant.support.TenantBypassScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 跨租户审计落库链路集成测试（REQ-20261125-001736 AC-3，Testcontainers PostgreSQL，
 * 无 Docker 自动跳过）：CrossTenantAuditLogger → CrossTenantAuditSink SPI → DB sink →
 * sys_cross_tenant_audit 真实 PG 落库（Flyway V19 建表；operator/tenant_id/access_point/
 * trace_id/bypass/created_at 列集与 operator+created_at 索引）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=infra-integration-test-secret-0123456789"
})
class REQ20261125001736CrossTenantAuditPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    /** 本地盘存储根（服务装配含 framework-storage；type=local 缺省，root-dir 必配，InfraIntegrationTest 同型）。 */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("audit-it-storage");
        } catch (IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
    }

    @Autowired
    private CrossTenantAuditLogger auditLogger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.execute("delete from sys_cross_tenant_audit");
    }

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        TenantContext.clear();
        TraceContext.clear();
    }

    @Test
    void should_persistDirectReadEvent_when_recordCalledWithTenantBound() throws Exception {
        UserContext.bind(new UserContext("admin-it-1", null));
        TenantContext.bind(new TenantContext("tenant-it-1"));
        TraceContext.bind(new TraceContext("trace-it-direct", "span-1"));

        auditLogger.record("it:cross-tenant-audit:direct");

        Map<String, Object> row = awaitRow("it:cross-tenant-audit:direct");
        assertThat(row.get("operator")).isEqualTo("admin-it-1");
        assertThat(row.get("tenant_id")).isEqualTo("tenant-it-1");
        assertThat(row.get("trace_id")).isEqualTo("trace-it-direct");
        assertThat(row.get("bypass")).isEqualTo("false");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void should_persistBypassRootEvent_when_recordCalledInsideBypassScope() throws Exception {
        UserContext.bind(new UserContext("admin-it-2", null));
        TraceContext.bind(new TraceContext("trace-it-bypass", "span-2"));

        try (TenantBypassScope ignored = TenantBypassScope.open("it:cross-tenant-audit:bypass")) {
            auditLogger.record("it:cross-tenant-audit:bypass");
        }

        Map<String, Object> row = awaitRow("it:cross-tenant-audit:bypass");
        assertThat(row.get("operator")).isEqualTo("admin-it-2");
        assertThat(row.get("tenant_id")).isEqualTo("*");
        assertThat(row.get("bypass")).isEqualTo("ROOT");
        assertThat(row.get("trace_id")).isEqualTo("trace-it-bypass");
    }

    @Test
    void should_fallbackSystemOperator_when_noUserContext() throws Exception {
        TenantContext.bind(new TenantContext("tenant-it-3"));

        auditLogger.record("it:cross-tenant-audit:system");

        Map<String, Object> row = awaitRow("it:cross-tenant-audit:system");
        assertThat(row.get("operator")).isEqualTo("system");
        assertThat(row.get("tenant_id")).isEqualTo("tenant-it-3");
        assertThat(row.get("bypass")).isEqualTo("false");
    }

    /** 轮询等待异步落库行（尽力口径亚秒延迟；10s 超时显式失败）。 */
    private Map<String, Object> awaitRow(String accessPoint) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select * from sys_cross_tenant_audit where access_point = ?", accessPoint);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        fail("跨租户审计行 10s 内未落库: " + accessPoint);
        return Map.of();
    }
}
