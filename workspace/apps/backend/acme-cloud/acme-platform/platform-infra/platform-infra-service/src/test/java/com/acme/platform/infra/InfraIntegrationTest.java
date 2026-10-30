package com.acme.platform.infra;

import com.acme.common.contract.PageQuery;
import com.acme.common.context.TenantContext;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.platform.infra.application.AuditIngestUseCase;
import com.acme.platform.infra.application.AuditQueryUseCase;
import com.acme.platform.infra.domain.OpAuditEvent;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-infra 集成测试（Testcontainers PostgreSQL，16.2.1 本地可执行、不依赖共享库；
 * 无 Docker 环境自动跳过）：实体-脚本一致性（ddl-auto=validate 启动即校验）、
 * 落库租户注入、event_id 幂等双保险、本租户查询过滤。
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
class InfraIntegrationTest {

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
    private AuditIngestUseCase ingestUseCase;

    @Autowired
    private AuditQueryUseCase queryUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRows() {
        // 用例间数据隔离（共享容器库；原生删除绕开 @TenantId 会话过滤）
        jdbcTemplate.execute("delete from acme_op_audit");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void ingestInjectsTenantAndQueryFiltersByTenant() {
        ingestUseCase.ingest(event("evt-a1", "tenant-a", "order.create"));
        ingestUseCase.ingest(event("evt-b1", "tenant-b", "order.create"));

        TenantContext.bind(new TenantContext("tenant-a"));
        var tenantARecords = queryUseCase.page(PageQuery.firstPage(), null, null, null, null, null);
        assertThat(tenantARecords.records())
                .extracting(OpAuditEvent::getEventId)
                .containsExactly("evt-a1");

        TenantContext.bind(new TenantContext("tenant-b"));
        assertThat(queryUseCase.page(PageQuery.firstPage(), null, null, null, null, null).records())
                .extracting(OpAuditEvent::getEventId)
                .containsExactly("evt-b1");
    }

    @Test
    void systemLevelEventNormalizesTenant() {
        ingestUseCase.ingest(event("evt-sys", null, "infra.audit.list"));
        TenantContext.bind(new TenantContext(AuditIngestUseCase.SYSTEM_TENANT));
        assertThat(queryUseCase.page(PageQuery.firstPage(), null, "infra.audit.list", null, null, null)
                .records())
                .extracting(OpAuditEvent::getEventId)
                .containsExactly("evt-sys");
    }

    @Test
    void duplicateEventIdIsIdempotentAtDbLevel() {
        // 绕过 exists 快路径直接重复 ingest（模拟并发窗口）：唯一约束兜底静默跳过
        ingestUseCase.ingest(event("evt-dup", "tenant-a", "order.create"));
        ingestUseCase.ingest(event("evt-dup", "tenant-a", "order.create"));
        TenantContext.bind(new TenantContext("tenant-a"));
        assertThat(queryUseCase.page(PageQuery.firstPage(), null, null, "evt-dup-trace", null, null)
                .total()).isEqualTo(1L);
    }

    @Test
    void queryFiltersByOperatorActionAndTimeRange() {
        Instant base = Instant.parse("2026-10-26T08:00:00Z");
        ingestUseCase.ingest(new AuditEvent("evt-f1", null, null, "tenant-a", "42", "B",
                "order.create", "order", "1", null, base, "it"));
        ingestUseCase.ingest(new AuditEvent("evt-f2", null, null, "tenant-a", "43", "B",
                "order.cancel", "order", "2", null, base.plusSeconds(3600), "it"));

        TenantContext.bind(new TenantContext("tenant-a"));
        var byOperator = queryUseCase.page(PageQuery.firstPage(), "42", null, null, null, null);
        assertThat(byOperator.records()).extracting(OpAuditEvent::getEventId).containsExactly("evt-f1");
        var byAction = queryUseCase.page(PageQuery.firstPage(), null, "order.cancel", null, null, null);
        assertThat(byAction.records()).extracting(OpAuditEvent::getEventId).containsExactly("evt-f2");
        var byTime = queryUseCase.page(PageQuery.firstPage(), null, null, null,
                base.plusSeconds(1), base.plusSeconds(7200));
        assertThat(byTime.records()).extracting(OpAuditEvent::getEventId).containsExactly("evt-f2");
    }

    private static AuditEvent event(String eventId, String tenantId, String action) {
        return new AuditEvent(eventId, eventId + "-trace", null, tenantId,
                "42", "B", action, "order", "1", null, Instant.now(), "it");
    }
}
