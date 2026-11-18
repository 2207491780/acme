package com.acme.platform.infra;

import com.acme.common.context.Channel;
import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.framework.core.support.AuthenticatedSubject;
import com.acme.framework.core.support.ClientContextDraft;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * REQ-20261117-061017 AC-6 导出上限截断（platform-infra）独立上下文集成测试
 * （Testcontainers PG + Redis；装配口径照抄 {@code REQ20261117061017OpExceptionIntegrationTest}；
 * 无 Docker 自动跳过）。
 *
 * <p>独立上下文的原因：上限截断需注入「小上限属性」（委派单口径），而主测试类按契约默认值
 * （10000 行）验证非截断语义，两者属性互斥——按 Spring 测试属性模型拆分为本类注入
 * {@code acme.excel.max-rows=2}（framework-excel ExcelProperties.maxRows 既有键，
 * REQ20261117061017OpExceptionIntegrationTest AMB-4）。</p>
 *
 * <p>覆盖：op-exceptions/export 与 audit-events/export 超限截断——X-Export-Truncated: true、
 * 文件名 .part 后缀、导出行数=上限值（+表头行）。</p>
 *
 * <p>口径说明：行数断言=数据行+表头行 1 行（StreamExcelWriter「表头先行」先例，AMB-5 同口径）；
 * 若上限实现为端点侧新属性/常量，本类注入键同步调整。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformInfraApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.stream.enabled=false",
        "acme.audit.enabled=false",
        "acme.security.token.hmac-secret=req061017-trunc-secret-012345678",
        "acme.excel.max-rows=2"
})
class REQ20261117061017ExportTruncationIntegrationTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 本地存储落盘根（framework-storage 本地模式 bean 装配必填，缺失显式失败 SYS-15-0006）。 */
    private static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("req061017-trunc-it-storage");
        } catch (IOException ex) {
            throw new IllegalStateException("创建本地盘存储根失败", ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("acme.storage.local.root-dir", () -> STORAGE_ROOT.toString());
    }

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private InternalTokenVerifier tokenVerifier;

    @Autowired
    private InternalTokenIssuer tokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuthorizationGateway authorizationGateway;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new InternalTokenAuthFilter(tokenVerifier, List.of()))
                .build();
        doAnswer(invocation -> null).when(authorizationGateway).requirePermission(anyString());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from sys_op_exception");
        jdbcTemplate.update("delete from acme_op_audit");
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-6：op-exceptions/export 超限截断（上限 2，种子 3 行） ----------

    @Test
    void should_truncateOpExceptionExport_when_rowsExceedInjectedLimit() throws Exception {
        seedOpException(910801, "platform-demo-a", "req061017-trunc-1", "/demo/trunc/1",
                "now() - interval '10 minutes'");
        seedOpException(910802, "platform-demo-a", "req061017-trunc-2", "/demo/trunc/2",
                "now() - interval '8 minutes'");
        seedOpException(910803, "platform-demo-a", "req061017-trunc-3", "/demo/trunc/3",
                "now() - interval '5 minutes'");

        MvcResult result = rawCall(get("/admin/v1/infra/op-exceptions/export"));

        assertThat(result.getResponse().getHeader("X-Export-Truncated"))
                .as("超上限应返回截断头 X-Export-Truncated: true").isEqualTo("true");
        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).as("截断导出文件名应带 .part 后缀").contains(".part");
        assertThat(result.getResponse().getContentType()).contains(XLSX_MIME);
        // 3 行超上限 2 → 导出 2 数据行 + 1 表头行
        assertThat(xlsxRowCount(result.getResponse().getContentAsByteArray())).isEqualTo(3);
    }

    // ---------- AC-6：audit-events/export 超限截断（上限 2，种子 3 行） ----------

    @Test
    void should_truncateAuditEventExport_when_rowsExceedInjectedLimit() throws Exception {
        seedAuditRow(910901, "req061017-trunc-audit-1", "now() - interval '10 minutes'");
        seedAuditRow(910902, "req061017-trunc-audit-2", "now() - interval '8 minutes'");
        seedAuditRow(910903, "req061017-trunc-audit-3", "now() - interval '5 minutes'");

        MvcResult result = rawCall(get("/admin/v1/infra/audit-events/export"));

        assertThat(result.getResponse().getHeader("X-Export-Truncated"))
                .as("超上限应返回截断头 X-Export-Truncated: true").isEqualTo("true");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains(".part");
        // 3 行超上限 2 → 导出 2 数据行 + 1 表头行
        assertThat(xlsxRowCount(result.getResponse().getContentAsByteArray())).isEqualTo(3);
    }

    // ---------- 支撑 ----------

    private void seedOpException(long id, String service, String traceId, String path, String occurredExpr) {
        jdbcTemplate.update("""
                insert into sys_op_exception (id, service, trace_id, span_id, tenant_id, operator_id,
                                              method, path, status_code, exception_type, message,
                                              stack_summary, occurred_at,
                                              created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, ?, 'req061017-span', 't-1', '1',
                        'GET', ?, 500, 'java.lang.IllegalStateException', 'seed-message',
                        'seed-stack-summary', %s,
                        now(), 'system', %s, 'system', 0, false)
                """.formatted(occurredExpr, occurredExpr),
                id, service, traceId, path);
    }

    private void seedAuditRow(long id, String eventId, String occurredExpr) {
        jdbcTemplate.update("""
                insert into acme_op_audit (id, event_id, trace_id, tenant_id, operator_id, channel,
                                           action, content, occurred_at,
                                           created_at, created_by, updated_at, updated_by, version, deleted)
                values (?, ?, 'req061017-trunc-trace', 't-1', '1', 'B',
                        'demo.audit', 'seed-content', %s,
                        now(), 'system', %s, 'system', 0, false)
                """.formatted(occurredExpr, occurredExpr),
                id, eventId);
    }

    /** xlsx 行元素计数（按 central directory 读 worksheet XML，不引新依赖）。
     *  不用 ZipInputStream：FastExcel/POI 写出的条目带 data descriptor（local header size=0、
     *  flags=0x8），JDK 25 ZipInputStream 对该布局误报 invalid entry size——产物本身
     *  Excel/ZipFile 均可正常打开，属流式解析器兼容面缺陷，故走临时文件 + ZipFile 解析。 */
    private static int xlsxRowCount(byte[] bytes) throws IOException {
        int rows = 0;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("req061017-trunc-export", ".xlsx");
        try {
            java.nio.file.Files.write(temp, bytes);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(temp.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("xl/worksheets/") && entry.getName().endsWith(".xml")) {
                        String xml = new String(zip.getInputStream(entry).readAllBytes(),
                                StandardCharsets.UTF_8);
                        int index = 0;
                        while ((index = xml.indexOf("<row", index)) >= 0) {
                            rows++;
                            index += 4;
                        }
                    }
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
        assertThat(rows).as("xlsx 应含 worksheet 行").isGreaterThan(0);
        return rows;
    }

    private MvcResult rawCall(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                .header("X-Acme-Channel", "B")
                .header("X-Acme-Token", tokenIssuer.issue(
                        new AuthenticatedSubject("1", "t-1", Channel.B, Set.of("admin"))))
                .requestAttr(ClientContextDraft.ATTRIBUTE_NAME,
                        new ClientContextDraft(null, "dev-1", "1.0")))
                .andReturn();
    }
}
